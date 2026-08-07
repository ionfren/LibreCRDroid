package re.abbot.librecr.app.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WearNodeRoutingTest {
    @Test
    fun `automatic routing accepts every source`() {
        assertTrue(WearNodeRouting.accepts("", "watch-a"))
        assertTrue(WearNodeRouting.accepts("", "watch-b"))
    }

    @Test
    fun `selected routing accepts only the selected source`() {
        assertTrue(WearNodeRouting.accepts("watch-b", "watch-b"))
        assertFalse(WearNodeRouting.accepts("watch-b", "watch-a"))
        assertFalse(WearNodeRouting.accepts("watch-b", ""))
    }

    @Test
    fun `selected routing targets only the selected connected watch`() {
        assertEquals(
            setOf("watch-b"),
            WearNodeRouting.targetNodeIds("watch-b", listOf("watch-a", "watch-b")),
        )
        assertTrue(
            WearNodeRouting.targetNodeIds("watch-c", listOf("watch-a", "watch-b")).isEmpty(),
        )
    }

    @Test
    fun `enforcement stops every non-selected watch`() {
        assertEquals(
            setOf("watch-a", "watch-c"),
            WearNodeRouting.nonSelectedNodeIds(
                "watch-b",
                listOf("watch-a", "watch-b", "watch-c"),
            ),
        )
    }
}
