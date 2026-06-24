package re.abbot.librecr.protocol.dataplane

/** Post-auth notify channel, identified by characteristic UUID prefix. */
enum class DataPlaneChannel(val uuidPrefix: String) {
    PATCH_CONTROL("08981338"),
    PATCH_STATUS("08981482"),
    GLUCOSE_DATA("0898177a"),
    HISTORIC_DATA("0898195a"),
    EVENT_LOG("08981bee"),
    CLINICAL_DATA("08981ab8"),
    FACTORY_DATA("08981d24");

    val preferredInboundKind: DataPlanePacketKind?
        get() = when (this) {
            PATCH_STATUS -> DataPlanePacketKind.KIND2
            GLUCOSE_DATA -> DataPlanePacketKind.KIND3
            HISTORIC_DATA -> DataPlanePacketKind.KIND4
            else -> null
        }

    companion object {
        fun fromUuid(uuidString: String): DataPlaneChannel? {
            val lower = uuidString.lowercase()
            return entries.firstOrNull { lower.startsWith(it.uuidPrefix) }
        }
    }
}

sealed class DataPlaneDecodedPayload {
    data class RealtimeGlucose(val reading: RealtimeGlucoseReading) : DataPlaneDecodedPayload()
    data class PatchStatusPayload(val status: PatchStatus) : DataPlaneDecodedPayload()
    data class HistoricalReadingPagePayload(val page: HistoricalReadingPage) : DataPlaneDecodedPayload()
    data class ClinicalReadingRecordPayload(val record: ClinicalReadingRecord) : DataPlaneDecodedPayload()
    data class Raw(val plaintext: ByteArray) : DataPlaneDecodedPayload()
}

data class DataPlaneDecodedPacket(
    val channel: DataPlaneChannel,
    val frame: DataFrame,
    val kind: DataPlanePacketKind,
    val preferredKind: DataPlanePacketKind?,
    val plaintext: ByteArray,
    val payload: DataPlaneDecodedPayload,
) {
    val usedPreferredKind: Boolean get() = preferredKind == null || preferredKind == kind
}

/** Decrypts + decodes a data-plane frame on a known channel. Port of Swift
 *  `DataPlaneDecoder`. */
class DataPlaneDecoder(val crypto: DataPlaneCrypto) {

    fun decrypt(frame: DataFrame, channel: DataPlaneChannel): DataPlaneDecodedPacket {
        val preferred = channel.preferredInboundKind
        val result: DataPlaneCrypto.DecryptResult = run {
            if (preferred != null) {
                val pt = try {
                    crypto.decrypt(frame, preferred)
                } catch (_: Exception) {
                    null
                }
                if (pt != null) return@run DataPlaneCrypto.DecryptResult(preferred, pt)
            }
            crypto.decryptTryingAllKinds(frame)
        }
        return DataPlaneDecodedPacket(
            channel = channel,
            frame = frame,
            kind = result.kind,
            preferredKind = preferred,
            plaintext = result.plaintext,
            payload = decodePayload(channel, result.plaintext),
        )
    }

    private fun decodePayload(channel: DataPlaneChannel, plaintext: ByteArray): DataPlaneDecodedPayload {
        return when (channel) {
            DataPlaneChannel.GLUCOSE_DATA ->
                runCatching { RealtimeGlucoseReading(plaintext) }.getOrNull()
                    ?.let { DataPlaneDecodedPayload.RealtimeGlucose(it) }
                    ?: DataPlaneDecodedPayload.Raw(plaintext)
            DataPlaneChannel.PATCH_STATUS ->
                runCatching { PatchStatus(plaintext) }.getOrNull()
                    ?.let { DataPlaneDecodedPayload.PatchStatusPayload(it) }
                    ?: DataPlaneDecodedPayload.Raw(plaintext)
            DataPlaneChannel.HISTORIC_DATA ->
                runCatching { HistoricalReadingPage(plaintext) }.getOrNull()
                    ?.let { DataPlaneDecodedPayload.HistoricalReadingPagePayload(it) }
                    ?: DataPlaneDecodedPayload.Raw(plaintext)
            DataPlaneChannel.CLINICAL_DATA ->
                runCatching { ClinicalReadingRecord(plaintext) }.getOrNull()
                    ?.let { DataPlaneDecodedPayload.ClinicalReadingRecordPayload(it) }
                    ?: DataPlaneDecodedPayload.Raw(plaintext)
            else -> DataPlaneDecodedPayload.Raw(plaintext)
        }
    }
}

/**
 * Reassembles glucose-data notifications before [DataFrame.parse]. Glucose
 * readings arrive as a 15-byte prefix + 20-byte suffix; concatenate before CCM
 * tag verification. Port of Swift `DataPlaneNotificationAssembler`.
 *
 * A buffered prefix whose suffix never arrives is an *orphan*: if it lingers it
 * will be glued to the next reading's first fragment, producing a frame that
 * fails CCM tag verification — i.e. a silently dropped reading. [orphanTimeoutMs]
 * bounds how long a prefix may wait; an older one is discarded so the incoming
 * fragment starts a clean pair. The clock is injectable for tests.
 */
class DataPlaneNotificationAssembler(
    private val orphanTimeoutMs: Long = DEFAULT_ORPHAN_TIMEOUT_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val lock = Any()
    private var glucosePrefix: ByteArray? = null
    private var glucosePrefixAtMs: Long = 0L

    /** Outcome of feeding one fragment, exposing orphan flushes for diagnostics. */
    data class FeedResult(
        val combined: ByteArray?,
        /** Age (ms) of a stale prefix discarded on this call, or null if none. */
        val flushedOrphanAgeMs: Long?,
        /** Age (ms) of a buffered prefix replaced by a fresh prefix before its suffix arrived. */
        val replacedPrefixAgeMs: Long? = null,
        /** Size of a suffix-like fragment received while no prefix was buffered. */
        val orphanSuffixSize: Int? = null,
    )

    fun feed(fragment: ByteArray, channel: DataPlaneChannel): ByteArray? =
        feedDetailed(fragment, channel).combined

    fun feedDetailed(fragment: ByteArray, channel: DataPlaneChannel): FeedResult {
        synchronized(lock) {
            if (channel != DataPlaneChannel.GLUCOSE_DATA) return FeedResult(fragment, null)
            val now = nowMs()
            var flushedAge: Long? = null
            var prefix = glucosePrefix
            if (prefix != null && now - glucosePrefixAtMs > orphanTimeoutMs) {
                flushedAge = now - glucosePrefixAtMs
                glucosePrefix = null
                prefix = null
            }
            if (prefix != null) {
                if (fragment.size == GLUCOSE_PREFIX_SIZE) {
                    val replacedAge = now - glucosePrefixAtMs
                    glucosePrefix = fragment
                    glucosePrefixAtMs = now
                    return FeedResult(null, flushedAge, replacedAge)
                }
                glucosePrefix = null
                return FeedResult(prefix + fragment, flushedAge)
            }
            if (fragment.size == GLUCOSE_PREFIX_SIZE) {
                glucosePrefix = fragment
                glucosePrefixAtMs = now
                return FeedResult(null, flushedAge)
            }
            return FeedResult(
                combined = fragment,
                flushedOrphanAgeMs = flushedAge,
                orphanSuffixSize = fragment.size.takeIf { it == GLUCOSE_SUFFIX_SIZE },
            )
        }
    }

    fun reset() {
        synchronized(lock) { glucosePrefix = null }
    }

    companion object {
        const val DEFAULT_ORPHAN_TIMEOUT_MS = 2_000L
        private const val GLUCOSE_PREFIX_SIZE = 15
        private const val GLUCOSE_SUFFIX_SIZE = 20
    }
}
