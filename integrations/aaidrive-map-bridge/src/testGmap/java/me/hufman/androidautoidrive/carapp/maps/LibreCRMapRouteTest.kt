package me.hufman.androidautoidrive.carapp.maps

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import com.google.android.gms.maps.model.LatLng
import me.hufman.androidautoidrive.maps.LatLong
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LibreCRMapRouteTest {
    @get:Rule val executor = InstantTaskExecutorRule()

    @Test fun routeIsCopiedAndStoppingNavigationClearsTheSharedOverlay() {
        val received = mutableListOf<LibreCRMapRoute.Snapshot>()
        val observer = Observer<LibreCRMapRoute.Snapshot> { received.add(it) }
        LibreCRMapRoute.current.observeForever(observer)
        try {
            val points = mutableListOf(LatLng(50.0, 5.0), LatLng(50.1, 5.1))
            LibreCRMapRoute.publish(LatLong(50.1, 5.1), points)
            points.clear()
            assertEquals(2, received.last().points.size)
            assertEquals(LatLng(50.1, 5.1), received.last().destination)
            LibreCRMapRoute.publish(null, null)
            assertTrue(received.last().points.isEmpty())
            assertNull(received.last().destination)
        } finally { LibreCRMapRoute.current.removeObserver(observer) }
    }
}
