package re.abbot.librecr.app.wear

internal enum class PeerAvailability {
    UNKNOWN,
    CONNECTED,
    DISCONNECTED,
}

/** Pure peer-gating policy kept separate so the energy-critical behavior is JVM-testable. */
internal object GlucoseDeliveryPolicy {
    /** Data Layer work is forbidden while the companion peer is absent or not yet resolved. */
    fun shouldAttemptTransport(peerAvailability: PeerAvailability): Boolean =
        peerAvailability == PeerAvailability.CONNECTED
}
