package re.abbot.librecr.app.idrive

import de.bmw.idrive.BMWRemoting
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class IDriveDiagnosticsTest {
    @Test fun `unwraps BMW errors that have a null standard exception message`() {
        val remote = BMWRemoting.ServiceException().apply { errorMsg = "RHMI application was already connected" }
        val result = idriveFailureDescription(IOException(remote))
        assertTrue(result.contains("ServiceException"))
        assertTrue(result.contains("RHMI application was already connected"))
    }

    @Test fun `sanitizes multiline errors and limits diagnostic size`() {
        val result = idriveFailureDescription(IllegalArgumentException("bad\ninput\t" + "x".repeat(2000)))
        assertFalse(result.contains('\n'))
        assertFalse(result.contains('\t'))
        assertTrue(result.length <= 800)
    }
}
