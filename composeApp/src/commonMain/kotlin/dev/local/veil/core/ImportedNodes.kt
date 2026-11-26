package dev.local.veil.core

fun mergeImportedNodes(
    existing: List<ProxyConfig>,
    incoming: List<ProxyConfig>,
    source: String?,
    replaceSource: Boolean,
): List<ProxyConfig> {
    val keys = incoming.mapTo(HashSet()) { it.dedupKey() }
    val base = existing.filterNot { replaceSource && source != null && it.source == source }
        .map { if (source != null && it.source == null && it.dedupKey() in keys) it.copy(source = source) else it }
    val known = base.filter { source == null || it.source == source }.mapTo(HashSet()) { it.dedupKey() }
    val previous = existing.filter { it.source == source }.associateBy { it.dedupKey() }
    val fresh = incoming.mapNotNull { node ->
        val key = node.dedupKey()
        if (!known.add(key)) null else node.copy(id = previous[key]?.id ?: node.id, source = source)
    }
    return base + fresh
}
