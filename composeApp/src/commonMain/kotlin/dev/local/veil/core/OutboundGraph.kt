package dev.local.veil.core

class OutboundGraph private constructor(

    val servers: List<ProxyConfig>,

    val groups: List<Pair<ServerGroup, List<ProxyConfig>>>,
) {

    val tags: Set<String> =
        servers.map { ProfileTags.server(it.id) }.toSet() + groups.map { ProfileTags.group(it.first.id) }

    val isEmpty: Boolean get() = servers.isEmpty() && groups.isEmpty()

    fun tagFor(target: RuleTarget): String {
        val tag = target.tag()
        return if (target.needsProfile && tag !in tags) ProfileTags.DEFAULT_SELECTOR else tag
    }

    companion object {

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
