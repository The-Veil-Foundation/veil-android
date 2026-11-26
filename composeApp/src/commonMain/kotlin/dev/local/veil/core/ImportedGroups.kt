package dev.local.veil.core

fun mergeImportedGroups(
    existing: List<ServerGroup>,
    imported: List<ServerGroup>,
    source: String?,
    parsedNodes: List<ProxyConfig>,
    storedNodes: List<ProxyConfig>,
): List<ServerGroup> {
    val idsByKey = storedNodes.filter { source == null || it.source == source }.associate { it.dedupKey() to it.id }
    val ids = parsedNodes.mapNotNull { node -> idsByKey[node.dedupKey()]?.let { node.id to it } }.toMap()
    val owned = existing.filter { it.source == source && it.sourceName != null }
    val next = imported.mapNotNull { group ->
        val members = group.memberIds.mapNotNull { ids[it] }.distinct()
        if (members.isEmpty()) return@mapNotNull null
        val old = owned.firstOrNull { it.sourceName == group.sourceName }
        group.copy(
            id = old?.id ?: group.id,
            name = old?.name ?: group.name,
            source = source,
            memberIds = members,
            selectedId = old?.selectedId?.takeIf { it in members } ?: members.first(),
        )
    }
    val names = imported.mapTo(HashSet()) { it.sourceName }
    return existing.filterNot {
        it.source == source && it.sourceName != null && (source != null || it.sourceName in names)
    } + next
}
