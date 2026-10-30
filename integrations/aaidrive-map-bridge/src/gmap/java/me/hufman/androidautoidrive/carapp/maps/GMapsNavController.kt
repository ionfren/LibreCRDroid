package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.maps.model.LatLng
import com.google.maps.DirectionsApi
import com.google.maps.DirectionsApiRequest
import com.google.maps.GeoApiContext
import com.google.maps.PendingResult
import com.google.maps.model.DirectionsResult
import com.google.maps.model.TravelMode
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import java.util.concurrent.TimeUnit

class GMapsNavController(val geoClient: GeoApiContext, val locationProvider: CarLocationProvider,
                         var callback: (GMapsNavController) -> Unit,
                         private val requestFactory: (GeoApiContext) -> DirectionsApiRequest = { DirectionsApi.newRequest(it) }) {
	companion object {
		fun getInstance(context: Context, locationProvider: CarLocationProvider, callback: (GMapsNavController) -> Unit): GMapsNavController {
			val api_key = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
					.metaData.getString("com.google.android.geo.API_KEY") ?: ""
			val geoClient = GeoApiContext().setQueryRateLimit(3)
					.setApiKey(api_key)
					.setConnectTimeout(5, TimeUnit.SECONDS)
					.setReadTimeout(5, TimeUnit.SECONDS)
					.setWriteTimeout(5, TimeUnit.SECONDS)
			return GMapsNavController(geoClient, locationProvider, callback)
		}
	}

	var currentNavDestination: LatLong? = null
		private set
	var currentNavRoute: List<LatLng>? = null
		private set
	var status = "IDLE"
		private set
	var distanceMeters = 0L
		private set
	var durationSeconds = 0L
		private set
	private var requestGeneration = 0L
	private var request: PendingResult<DirectionsResult>? = null

	@Synchronized fun navigateTo(dest: LatLong) {
		requestGeneration++
		request?.cancel()
		request = null
		currentNavDestination = dest
		currentNavRoute = null
		distanceMeters = 0L
		durationSeconds = 0L
		val currentLocation = locationProvider.currentLocation
		status = if (currentLocation == null) "NO_LOCATION" else "CALCULATING"
		callback(this)
		if (currentLocation != null) routeNavigation(LatLong(currentLocation.latitude, currentLocation.longitude), dest, requestGeneration)
	}

	@Synchronized fun stopNavigation() {
		requestGeneration++
		request?.cancel()
		request = null
		currentNavDestination = null
		currentNavRoute = null
		status = "IDLE"
		distanceMeters = 0L
		durationSeconds = 0L
		callback(this)
	}

	private fun routeNavigation(start: LatLong, dest: LatLong, generation: Long) {
		// start a route search
		val origin = com.google.maps.model.LatLng(start.latitude, start.longitude)
		val routeDest = com.google.maps.model.LatLng(dest.latitude, dest.longitude)

		val directionsRequest = requestFactory(geoClient)
				.mode(TravelMode.DRIVING)
				.origin(origin)
				.destination(routeDest)
		request = directionsRequest
		directionsRequest.setCallback(object: PendingResult.Callback<DirectionsResult> {
			override fun onFailure(e: Throwable?) {
				synchronized(this@GMapsNavController) {
					if (generation != requestGeneration) return
					status = "ERROR"
					callback(this@GMapsNavController)
				}
			}

			override fun onResult(result: DirectionsResult?) {
				synchronized(this@GMapsNavController) {
					if (generation != requestGeneration) return
					val route = result?.routes?.firstOrNull()
					val decodedPath = route?.overviewPolyline?.decodePath()
					if (decodedPath.isNullOrEmpty()) {
						status = "ERROR"
					} else {
						currentNavRoute = decodedPath.map { LatLng(it.lat, it.lng) }
						distanceMeters = route.legs?.sumOf { it.distance?.inMeters ?: 0L } ?: 0L
						durationSeconds = route.legs?.sumOf { it.duration?.inSeconds ?: 0L } ?: 0L
						status = "ACTIVE"
					}
					callback(this@GMapsNavController)
				}
			}
		})
	}
}
