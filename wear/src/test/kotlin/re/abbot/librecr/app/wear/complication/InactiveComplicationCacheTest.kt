package re.abbot.librecr.app.wear.complication

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InactiveComplicationCacheTest {
    private val cache = InactiveComplicationCache<String>()
    private val glucose = listOf("value", "delta")

    @Test
    fun `unknown configuration after process restart never skips a reading`() {
        assertFalse(cache.canSkip(glucose))
        cache.record(cache.version, glucose, emptySet())
        assertTrue(cache.canSkip(glucose))
        assertFalse(InactiveComplicationCache<String>().canSkip(glucose))
    }

    @Test
    fun `a confirmed inactive configuration skips subsequent glucose bursts`() {
        cache.record(cache.version, glucose, emptySet())
        repeat(100) { assertTrue(cache.canSkip(glucose)) }
    }

    @Test
    fun `one active glucose source keeps the live update path enabled`() {
        cache.record(cache.version, glucose, setOf("value"))
        assertFalse(cache.canSkip(glucose))
        assertFalse(cache.canSkip(listOf("value")))
        assertTrue(cache.canSkip(listOf("delta")))
    }

    @Test
    fun `an active date source does not require a glucose burst`() {
        val all = glucose + "date"
        cache.record(cache.version, all, setOf("date"))
        assertTrue(cache.canSkip(glucose))
        assertFalse(cache.canSkip(all))
    }

    @Test
    fun `activation or host request immediately invalidates an inactive result`() {
        cache.record(cache.version, glucose, emptySet())
        cache.configurationChanged()
        assertFalse(cache.canSkip(glucose))
    }

    @Test
    fun `late inactive discovery cannot undo a newer activation`() {
        val oldVersion = cache.version
        cache.configurationChanged()
        cache.record(oldVersion, glucose, emptySet())
        assertFalse(cache.canSkip(glucose))
    }

    @Test
    fun `querying one source does not classify unqueried sources as inactive`() {
        cache.record(cache.version, listOf("date"), emptySet())
        assertFalse(cache.canSkip(glucose))
        assertTrue(cache.canSkip(listOf("date")))
    }
}
