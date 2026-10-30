package re.abbot.librecr.app.wear.complication

/** Process-local negative results from the system, invalidated by source lifecycle callbacks. */
internal class InactiveComplicationCache<T> {
    var version = 0L
        private set
    private val inactive = mutableSetOf<T>()

    fun canSkip(sources: Collection<T>): Boolean = sources.isNotEmpty() && inactive.containsAll(sources)

    fun configurationChanged() {
        version++
        inactive.clear()
    }

    /** A query started before an activation/request must not put the source back to sleep. */
    fun record(queryVersion: Long, queried: Collection<T>, active: Set<T>) {
        if (queryVersion != version) return
        inactive.removeAll(queried.toSet())
        inactive.addAll(queried.filterNot { it in active })
    }
}
