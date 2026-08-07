package re.abbot.librecr.app.wear

/**
 * Pure routing rules shared by the Wear transport and its unit tests.
 *
 * An empty selection preserves the original single-watch behavior. Once a watch is selected,
 * messages are accepted from and sent to that node only.
 */
internal object WearNodeRouting {
    fun accepts(selectedNodeId: String, sourceNodeId: String): Boolean =
        selectedNodeId.isBlank() || selectedNodeId == sourceNodeId

    fun targetNodeIds(selectedNodeId: String, connectedNodeIds: Collection<String>): Set<String> =
        if (selectedNodeId.isBlank()) {
            connectedNodeIds.toSet()
        } else {
            connectedNodeIds.filterTo(linkedSetOf()) { it == selectedNodeId }
        }

    fun nonSelectedNodeIds(selectedNodeId: String, connectedNodeIds: Collection<String>): Set<String> =
        connectedNodeIds.filterTo(linkedSetOf()) { it != selectedNodeId }
}
