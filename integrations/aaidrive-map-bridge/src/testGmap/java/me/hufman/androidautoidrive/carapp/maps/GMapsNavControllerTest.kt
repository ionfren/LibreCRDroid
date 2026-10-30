package me.hufman.androidautoidrive.carapp.maps

import android.location.Location
import com.google.maps.DirectionsApiRequest
import com.google.maps.GeoApiContext
import com.google.maps.PendingResult
import com.google.maps.model.*
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers
import org.mockito.kotlin.*

class GMapsNavControllerTest {
    private val location = mock<Location> {
        on { latitude } doReturn 50.0
        on { longitude } doReturn 4.0
    }
    private val provider = mock<CarLocationProvider> { on { currentLocation } doReturn location }
    private val requests = mutableListOf<DirectionsApiRequest>()
    private val callbacks = mutableListOf<PendingResult.Callback<DirectionsResult>>()
    private val statuses = mutableListOf<String>()
    private val controller = GMapsNavController(mock<GeoApiContext>(), provider, { statuses.add(it.status) }) {
        mock<DirectionsApiRequest>(defaultAnswer = Answers.RETURNS_SELF).also { request ->
            doAnswer {
                callbacks.add(it.getArgument(0))
                null
            }.whenever(request).setCallback(any())
            requests.add(request)
        }
    }

    private fun result() = DirectionsResult().apply {
        routes = arrayOf(DirectionsRoute().apply {
            overviewPolyline = EncodedPolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
            legs = arrayOf(DirectionsLeg().apply {
                distance = Distance().apply { inMeters = 2500L }
                duration = Duration().apply { inSeconds = 600L }
            })
        })
    }

    @Test fun stoppedRouteCannotBeRestoredByALateDirectionsResponse() {
        controller.navigateTo(LatLong(51.0, 5.0))
        assertEquals("CALCULATING", controller.status)
        controller.stopNavigation()
        callbacks.first().onResult(result())
        verify(requests.first()).cancel()
        assertEquals("IDLE", controller.status)
        assertNull(controller.currentNavDestination)
        assertNull(controller.currentNavRoute)
        assertEquals(listOf("CALCULATING", "IDLE"), statuses)
    }

    @Test fun newDestinationRejectsOlderSuccessAndFailureAndReportsRouteTotals() {
        controller.navigateTo(LatLong(51.0, 5.0))
        controller.navigateTo(LatLong(52.0, 6.0))
        callbacks.first().onResult(result())
        callbacks.first().onFailure(IllegalStateException())
        assertEquals("CALCULATING", controller.status)
        assertNull(controller.currentNavRoute)
        callbacks.last().onResult(result())
        assertEquals("ACTIVE", controller.status)
        assertEquals(2500L, controller.distanceMeters)
        assertEquals(600L, controller.durationSeconds)
        assertEquals(3, controller.currentNavRoute!!.size)
        controller.stopNavigation()
        assertEquals(0L, controller.distanceMeters)
    }

    @Test fun missingGpsAndNoRouteProduceExplicitStatuses() {
        whenever(provider.currentLocation).thenReturn(null)
        controller.navigateTo(LatLong(51.0, 5.0))
        assertEquals("NO_LOCATION", controller.status)
        assertTrue(requests.isEmpty())
        whenever(provider.currentLocation).thenReturn(location)
        controller.navigateTo(LatLong(51.0, 5.0))
        callbacks.last().onResult(DirectionsResult().apply { routes = emptyArray() })
        assertEquals("ERROR", controller.status)
        assertNull(controller.currentNavRoute)
    }
}
