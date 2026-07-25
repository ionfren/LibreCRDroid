package re.abbot.librecr.protocol.dataplane

import java.util.TreeSet

/**
 * Tracks the highest contiguous sensor minute without forgetting values which arrive beyond a gap.
 *
 * [seedLastContiguousLifeCount] is the last minute already known to be contiguous. When no seed is
 * available, the first recorded minute establishes the baseline; minutes before that first observation
 * cannot safely be inferred as missing.
 *
 * The tracker is synchronized because realtime and clinical-backfill records can be consumed by
 * separate coroutines.
 */
class ContiguousLifeCountTracker(
    seedLastContiguousLifeCount: Int? = null,
) {
    private val lock = Any()
    private var contiguousLifeCount: Int? = seedLastContiguousLifeCount
    private val receivedBeyondGap = TreeSet<Int>()

    val lastContiguousLifeCount: Int?
        get() = synchronized(lock) { contiguousLifeCount }

    val nextMissingLifeCount: Int?
        get() = synchronized(lock) { nextMissingLifeCountLocked() }

    /**
     * Records one received minute and returns true only when the contiguous cursor advances.
     * A value beyond a gap is retained until all preceding missing minutes arrive.
     */
    fun record(lifeCount: Int): Boolean = synchronized(lock) {
        val previous = contiguousLifeCount
        if (previous == null) {
            contiguousLifeCount = lifeCount
            return@synchronized true
        }
        if (lifeCount <= previous) return@synchronized false

        receivedBeyondGap.add(lifeCount)
        var cursor = previous
        while (cursor < Int.MAX_VALUE && receivedBeyondGap.remove(cursor + 1)) {
            cursor += 1
        }
        contiguousLifeCount = cursor
        cursor != previous
    }

    /**
     * Advances over a prefix which the sensor has proved is no longer retained.
     *
     * Call this only for the first record returned by a clinical `>= requested` backfill. When that
     * first record is newer than requested, every minute before it is outside the sensor's replay
     * window; keeping the cursor behind that unavailable prefix would request the same impossible
     * range forever.
     */
    fun skipUnavailableBefore(firstAvailableLifeCount: Int): Boolean = synchronized(lock) {
        val previous = contiguousLifeCount ?: return@synchronized false
        val lastUnavailable = firstAvailableLifeCount - 1
        if (lastUnavailable <= previous) return@synchronized false
        contiguousLifeCount = lastUnavailable
        receivedBeyondGap.headSet(firstAvailableLifeCount).clear()
        true
    }

    /**
     * Returns the first minute to request from the clinical backfill stream, but only when the sensor's
     * [currentLifeCount] proves that minute is actually missing. Equality means the next live minute has
     * not arrived yet and therefore does not justify a backfill request.
     */
    fun backfillStart(currentLifeCount: Int): Int? = synchronized(lock) {
        nextMissingLifeCountLocked()?.takeIf { it < currentLifeCount }
    }

    private fun nextMissingLifeCountLocked(): Int? =
        contiguousLifeCount?.takeIf { it < Int.MAX_VALUE }?.plus(1)
}
