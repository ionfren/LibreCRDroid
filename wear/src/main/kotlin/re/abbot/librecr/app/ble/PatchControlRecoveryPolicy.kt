package re.abbot.librecr.app.ble

import re.abbot.librecr.protocol.dataplane.DataFrame

internal const val GATT_CCC_CONFIGURATION_ERROR_STATUS = 0xFD
internal const val GATT_PROCEDURE_ALREADY_IN_PROGRESS_STATUS = 0xFE

internal enum class PatchControlWriteRecovery {
    REARM_CCCDS_AND_RETRY,
    GIVE_UP,
}

internal enum class PatchControlWriteResult {
    COMPLETED,
    DEFINITELY_REJECTED,
    INDETERMINATE_OR_ACTIVE,
}

internal class PatchControlSequenceExhaustedException :
    IllegalStateException("PATCH_CONTROL sequence exhausted; a fresh authenticated session is required")

internal fun patchControlWriteRecovery(
    status: Int,
    cccdRetryUsed: Boolean,
): PatchControlWriteRecovery = when {
    status == GATT_CCC_CONFIGURATION_ERROR_STATUS && !cccdRetryUsed ->
        PatchControlWriteRecovery.REARM_CCCDS_AND_RETRY
    else -> PatchControlWriteRecovery.GIVE_UP
}

/** Null means this authenticated session consumed its final safe CCM nonce. */
internal fun nextPatchControlSequence(sequence: Int): Int? =
    if (sequence !in 1 until 0xFFFF) null else sequence + 1

internal fun patchControlCompletionSequence(raw: ByteArray): Int? =
    runCatching { DataFrame.parse(raw).sequenceNumber }.getOrNull()

internal fun shouldCoalesceBackfill(
    coveredFromLifeCount: Int,
    requestedFromLifeCount: Int,
    coverageActive: Boolean,
    coverageAgeMs: Long,
    cooldownMs: Long,
): Boolean =
    requestedFromLifeCount >= coveredFromLifeCount &&
        (coverageActive || coverageAgeMs in 0 until cooldownMs)
