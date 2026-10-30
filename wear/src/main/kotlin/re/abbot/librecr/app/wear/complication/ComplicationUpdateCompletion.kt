package re.abbot.librecr.app.wear.complication

/** Tracks responses to the latest update burst. Access is serialized by the wakelock owner. */
internal class ComplicationUpdateCompletion<T> {
    private var generation = 0L
    private var dispatchFinished = false
    // An empty set means discovery is unavailable: wait for the first response from that source.
    private var pending = mutableMapOf<T, MutableSet<Int>>()

    fun begin(targets: Map<T, Set<Int>>): Long {
        generation++
        setTargets(generation, targets)
        return generation
    }

    fun setTargets(token: Long, targets: Map<T, Set<Int>>) {
        if (token != generation) return
        dispatchFinished = false
        pending = targets.mapValues { it.value.toMutableSet() }.toMutableMap()
    }

    fun requestStarted(source: T, instanceId: Int): Long? =
        pending[source]?.takeIf { it.isEmpty() || instanceId in it }?.let { generation }

    fun responseDelivered(token: Long, source: T, instanceId: Int) {
        if (token != generation) return
        val ids = pending[source] ?: return
        if (ids.isEmpty() || (ids.remove(instanceId) && ids.isEmpty())) pending.remove(source)
    }

    fun requestFailed(token: Long, source: T) {
        if (token == generation) pending.remove(source)
    }

    fun finishDispatch(token: Long) {
        if (token == generation) dispatchFinished = true
    }

    fun canRelease(token: Long): Boolean =
        token == generation && dispatchFinished && pending.isEmpty()
}
