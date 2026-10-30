package re.abbot.librecr.app.idrive

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IDriveEndpointTest {
    private fun intent() = Intent(IDriveEndpoint.ACTION)
        .putExtra("EXTRA_BRAND", "bmw").putExtra("EXTRA_HOST", "127.0.0.1")
        .putExtra("EXTRA_PORT", 4007).putExtra("EXTRA_INSTANCE_ID", 1)

    @Test fun `accepts AAIdrive local tunnel announcement`() {
        assertEquals(IDriveEndpoint("bmw", "127.0.0.1", 4007, 1), IDriveEndpoint.fromIntent(intent()))
    }

    @Test fun `rejects remote hosts incomplete announcements and invalid endpoints`() {
        assertNull(IDriveEndpoint.fromIntent(null))
        listOf(
            intent().setAction("other"),
            intent().putExtra("EXTRA_HOST", "192.168.1.2"),
            intent().putExtra("EXTRA_PORT", 0),
            intent().putExtra("EXTRA_PORT", 65536),
            intent().putExtra("EXTRA_BRAND", "other"),
            Intent(IDriveEndpoint.ACTION),
        ).forEach { assertNull(IDriveEndpoint.fromIntent(it)) }
    }

    @Test fun `RHMI does not require the AV instance identifier`() {
        val missing = intent().apply { removeExtra("EXTRA_INSTANCE_ID") }
        assertEquals(IDriveEndpoint("bmw", "127.0.0.1", 4007, -1), IDriveEndpoint.fromIntent(missing))
        assertNotNull(IDriveEndpoint.fromIntent(intent().putExtra("EXTRA_INSTANCE_ID", -1)))
    }
}
