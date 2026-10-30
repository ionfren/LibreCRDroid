package re.abbot.librecr.app.wear.complication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComplicationUpdateCompletionTest {
    private val completion = ComplicationUpdateCompletion<String>()

    @Test
    fun `waits for both glucose sources and every active instance`() {
        val token = completion.begin(mapOf("value" to setOf(1, 2), "delta" to setOf(3)))
        completion.finishDispatch(token)
        completion.responseDelivered(token, "value", 1)
        completion.responseDelivered(token, "delta", 3)
        assertFalse(completion.canRelease(token))
        completion.responseDelivered(token, "value", 2)
        assertTrue(completion.canRelease(token))
    }

    @Test
    fun `a fast response cannot release while the second source is being dispatched`() {
        val token = completion.begin(mapOf("value" to setOf(1)))
        completion.responseDelivered(token, "value", 1)
        assertFalse(completion.canRelease(token))
        completion.finishDispatch(token)
        assertTrue(completion.canRelease(token))
    }

    @Test
    fun `an older callback cannot acknowledge or release a newer reading`() {
        val older = completion.begin(mapOf("value" to setOf(1)))
        val callback = requireNotNull(completion.requestStarted("value", 1))
        val newer = completion.begin(mapOf("value" to setOf(1)))
        completion.finishDispatch(newer)
        completion.responseDelivered(callback, "value", 1)
        completion.finishDispatch(older)
        completion.requestFailed(older, "value")
        assertFalse(completion.canRelease(older))
        assertFalse(completion.canRelease(newer))
        assertEquals(newer, completion.requestStarted("value", 1))
        completion.responseDelivered(newer, "value", 1)
        assertTrue(completion.canRelease(newer))
    }

    @Test
    fun `a release already queued for the previous reading cannot release the next one`() {
        val older = completion.begin(emptyMap())
        completion.finishDispatch(older)
        assertTrue(completion.canRelease(older))
        val newer = completion.begin(mapOf("value" to setOf(1)))
        assertFalse(completion.canRelease(older))
        assertFalse(completion.canRelease(newer))
    }

    @Test
    fun `missing host responses retain the safety timeout path`() {
        val token = completion.begin(mapOf("value" to setOf(1)))
        completion.finishDispatch(token)
        completion.responseDelivered(token, "value", 2)
        assertNull(completion.requestStarted("value", 2))
        assertFalse(completion.canRelease(token))
    }

    @Test
    fun `authoritative empty active configuration needs no callback`() {
        val token = completion.begin(mapOf("value" to emptySet()))
        completion.setTargets(token, emptyMap())
        completion.finishDispatch(token)
        assertTrue(completion.canRelease(token))
    }

    @Test
    fun `discovery after restart replaces stale instance ids`() {
        val token = completion.begin(mapOf("value" to setOf(1)))
        completion.setTargets(token, mapOf("value" to setOf(22)))
        completion.finishDispatch(token)
        completion.responseDelivered(token, "value", 1)
        assertFalse(completion.canRelease(token))
        completion.responseDelivered(token, "value", 22)
        assertTrue(completion.canRelease(token))
    }

    @Test
    fun `late discovery from an older reading cannot overwrite newer targets`() {
        val older = completion.begin(mapOf("value" to setOf(1)))
        val newer = completion.begin(mapOf("value" to setOf(2)))
        completion.setTargets(older, mapOf("value" to setOf(1)))
        completion.finishDispatch(newer)
        completion.responseDelivered(newer, "value", 2)
        assertTrue(completion.canRelease(newer))
    }

    @Test
    fun `fallback on older Wear OS accepts a callback without known instance ids`() {
        val token = completion.begin(mapOf("value" to emptySet(), "delta" to emptySet()))
        completion.finishDispatch(token)
        assertEquals(token, completion.requestStarted("value", 100))
        completion.responseDelivered(token, "value", 100)
        assertFalse(completion.canRelease(token))
        completion.responseDelivered(token, "delta", 101)
        assertTrue(completion.canRelease(token))
    }
}
