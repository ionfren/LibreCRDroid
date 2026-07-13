package re.abbot.librecr.app.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseDeliveryPolicyTest {
    @Test
    fun `transport is paused unless peer is confirmed connected`() {
        assertTrue(GlucoseDeliveryPolicy.shouldAttemptTransport(PeerAvailability.CONNECTED))
        assertFalse(GlucoseDeliveryPolicy.shouldAttemptTransport(PeerAvailability.UNKNOWN))
        assertFalse(GlucoseDeliveryPolicy.shouldAttemptTransport(PeerAvailability.DISCONNECTED))
    }
}
