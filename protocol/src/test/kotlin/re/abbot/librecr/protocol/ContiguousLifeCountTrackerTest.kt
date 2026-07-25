package re.abbot.librecr.protocol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import re.abbot.librecr.protocol.dataplane.ContiguousLifeCountTracker
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class ContiguousLifeCountTrackerTest {
    @Test
    fun `value above a gap is retained without advancing the contiguous cursor`() {
        val tracker = ContiguousLifeCountTracker(seedLastContiguousLifeCount = 100)

        assertFalse(tracker.record(103))
        assertEquals(100, tracker.lastContiguousLifeCount)
        assertEquals(101, tracker.nextMissingLifeCount)
        assertEquals(101, tracker.backfillStart(currentLifeCount = 103))
        assertNull(tracker.backfillStart(currentLifeCount = 101))
    }

    @Test
    fun `missing minutes automatically bridge to a previously received later value`() {
        val tracker = ContiguousLifeCountTracker(seedLastContiguousLifeCount = 100)
        tracker.record(103)

        assertTrue(tracker.record(101))
        assertEquals(101, tracker.lastContiguousLifeCount)
        assertEquals(102, tracker.nextMissingLifeCount)
        assertEquals(102, tracker.backfillStart(currentLifeCount = 103))

        assertTrue(tracker.record(102))
        assertEquals(103, tracker.lastContiguousLifeCount)
        assertEquals(104, tracker.nextMissingLifeCount)
        assertNull(tracker.backfillStart(currentLifeCount = 104))
    }

    @Test
    fun `duplicates and out of order values do not move the cursor`() {
        val tracker = ContiguousLifeCountTracker(seedLastContiguousLifeCount = 100)

        assertFalse(tracker.record(102))
        assertFalse(tracker.record(102))
        assertFalse(tracker.record(100))
        assertFalse(tracker.record(99))
        assertEquals(100, tracker.lastContiguousLifeCount)
        assertEquals(101, tracker.nextMissingLifeCount)

        assertTrue(tracker.record(101))
        assertEquals(102, tracker.lastContiguousLifeCount)
        assertEquals(103, tracker.nextMissingLifeCount)
    }

    @Test
    fun `without a seed the first record establishes the baseline`() {
        val tracker = ContiguousLifeCountTracker()

        assertNull(tracker.lastContiguousLifeCount)
        assertNull(tracker.nextMissingLifeCount)
        assertNull(tracker.backfillStart(currentLifeCount = 500))

        assertTrue(tracker.record(500))
        assertEquals(500, tracker.lastContiguousLifeCount)
        assertEquals(501, tracker.nextMissingLifeCount)

        assertFalse(tracker.record(503))
        assertEquals(501, tracker.backfillStart(currentLifeCount = 503))
        assertTrue(tracker.record(501))
        assertTrue(tracker.record(502))
        assertEquals(503, tracker.lastContiguousLifeCount)
    }

    @Test
    fun `unavailable replay prefix is skipped once the sensor reveals its first retained minute`() {
        val tracker = ContiguousLifeCountTracker(seedLastContiguousLifeCount = 100)
        tracker.record(202)

        assertTrue(tracker.skipUnavailableBefore(firstAvailableLifeCount = 200))
        assertEquals(199, tracker.lastContiguousLifeCount)
        assertTrue(tracker.record(200))
        assertEquals(200, tracker.lastContiguousLifeCount)
        assertTrue(tracker.record(201))
        assertEquals(202, tracker.lastContiguousLifeCount)
    }

    @Test
    fun `unavailable prefix skip never moves the cursor backwards`() {
        val tracker = ContiguousLifeCountTracker(seedLastContiguousLifeCount = 200)

        assertFalse(tracker.skipUnavailableBefore(firstAvailableLifeCount = 200))
        assertFalse(tracker.skipUnavailableBefore(firstAvailableLifeCount = 199))
        assertEquals(200, tracker.lastContiguousLifeCount)
    }

    @Test
    fun `concurrent realtime and backfill records preserve a contiguous cursor`() {
        val tracker = ContiguousLifeCountTracker(seedLastContiguousLifeCount = 100)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = (101..500)
                .shuffled(Random(8))
                .map { lifeCount -> executor.submit<Boolean> { tracker.record(lifeCount) } }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(500, tracker.lastContiguousLifeCount)
        assertEquals(501, tracker.nextMissingLifeCount)
    }
}
