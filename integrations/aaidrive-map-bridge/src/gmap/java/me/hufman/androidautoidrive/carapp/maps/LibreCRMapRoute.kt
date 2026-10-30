package me.hufman.androidautoidrive.carapp.maps

import androidx.lifecycle.MutableLiveData
import com.google.android.gms.maps.model.LatLng
import me.hufman.androidautoidrive.maps.LatLong

/** Shares the route already calculated by AAIdrive without another Directions API request. */
object LibreCRMapRoute {
    data class Snapshot(val destination: LatLng?, val points: List<LatLng>,
                        val status: String = "IDLE", val distanceMeters: Long = 0, val durationSeconds: Long = 0)
    val current = MutableLiveData<Snapshot>()
    fun publish(destination: LatLong?, route: List<LatLng>?, status: String = "IDLE",
                distanceMeters: Long = 0, durationSeconds: Long = 0) {
        current.postValue(Snapshot(destination?.let { LatLng(it.latitude, it.longitude) }, route?.toList().orEmpty(),
            status, distanceMeters, durationSeconds))
    }
}
