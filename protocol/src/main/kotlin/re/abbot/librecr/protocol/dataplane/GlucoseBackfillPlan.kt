package re.abbot.librecr.protocol.dataplane

/**
 * Commands used to repair the minute-resolution glucose timeline.
 *
 * `CLINICAL_DATA.currentGlucose` is the same per-minute series as realtime glucose. The historical
 * stream is a separately finalized/filtered five-minute series; interleaving it with clinical
 * values produces periodic false spikes, so it must never be part of a minute-chart repair.
 */
fun glucoseChartBackfillCommands(fromLifeCount: Int): List<PatchControlCommand> =
    listOf(
        PatchControlCommand.clinicalBackfillGreaterEqual(fromLifeCount.coerceAtLeast(0)),
    )
