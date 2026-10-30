package me.hufman.androidautoidrive.carapp.maps

import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Answers
import org.mockito.kotlin.*

class LibreCRMapServiceTest {
    @Test fun onlyLibreCRUidCanRequestLocationBearingFrames() {
        val pm = mock<PackageManager>()
        val service = mock<LibreCRMapService>(defaultAnswer = Answers.CALLS_REAL_METHODS)
        doReturn(pm).whenever(service).packageManager
        whenever(pm.getPackagesForUid(1001)).thenReturn(arrayOf("re.abbot.librecr.app"))
        whenever(pm.getPackagesForUid(1002)).thenReturn(arrayOf("some.other.app"))
        whenever(pm.getPackagesForUid(1003)).thenReturn(null)
        assertTrue(service.isTrustedUid(1001))
        assertFalse(service.isTrustedUid(1002))
        assertFalse(service.isTrustedUid(1003))
    }
}
