package dev.local.veil.core

/**
 * Узлы и группы, которые правила называют по имени (`RuleTarget.SERVER` /
 * `RuleTarget.GROUP`), разрешённые для конкретного ядра.
 *
 * Активный узел всегда живёт под тегом `proxy`; всё, что здесь, — это
 * дополнительные выходы, которые нужны только правилам. Узел, который ядро не
 * умеет, в граф не попадает, и правило падает обратно на `proxy` — оно просило
 * прокси, не хватает лишь названного узла.
 */
class OutboundGraph private constructor(
    /** Узлы с тегом [ProfileTags.server]. */
    val servers: List<ProxyConfig>,
    /** Группы с тегом [ProfileTags.group] и их участники (уже отфильтрованные). */
    val groups: List<Pair<ServerGroup, List<ProxyConfig>>>,
) {
    /** Все теги, которые объявит конфиг, кроме proxy/direct/block. */
    val tags: Set<String> =
        servers.map { ProfileTags.server(it.id) }.toSet() + groups.map { ProfileTags.group(it.first.id) }

    val isEmpty: Boolean get() = servers.isEmpty() && groups.isEmpty()

    /** Тег для цели правила с откатом на `proxy`, если выход не построен. */
    fun tagFor(target: RuleTarget): String {
        val tag = target.tag()
        return if (target.needsProfile && tag !in tags) ProfileTags.DEFAULT_SELECTOR else tag
    }

    companion object {
        /**
         * @param capable умеет ли ядро этот узел.
         * @param extraTargets цели вне правил (например, DNS-детуры).
         */
        fun build(
            rules: List<RoutingRule>,
            servers: List<ProxyConfig>,
            groups: List<ServerGroup>,
            capable: (ProxyConfig) -> Boolean,
            extraTargets: List<RuleTarget> = emptyList(),
        ): OutboundGraph {
            val targets = (rules.filter { it.enabled }.map { it.target } + extraTargets)
                .filter { it.needsProfile }
                .distinct()
            val byId = servers.associateBy { it.id }
            val wantedServers = LinkedHashMap<String, ProxyConfig>()
            val builtGroups = mutableListOf<Pair<ServerGroup, List<ProxyConfig>>>()

            for (target in targets) {
                val id = target.id ?: continue
                when (target.kind) {
                    TargetKind.SERVER -> byId[id]?.takeIf(capable)?.let { wantedServers[it.id] = it }
                    TargetKind.GROUP -> {
                        val group = groups.firstOrNull { it.id == id } ?: continue
                        val members = group.members(servers).filter(capable)
                        if (members.isEmpty()) continue
                        members.forEach { wantedServers[it.id] = it }
                        builtGroups += group to members
                    }
                    else -> Unit
                }
            }
            return OutboundGraph(wantedServers.values.toList(), builtGroups)
        }
    }
}
