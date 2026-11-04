package dev.local.veil.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/* ------------------------------------------------------------------ */
/* Маршрутизация — порт Routing.swift из veil-ios                       */
/* ------------------------------------------------------------------ */

/** Куда правило отправляет трафик. */
@Serializable
enum class TargetKind { PROXY, DIRECT, BLOCK, SERVER, GROUP }

@Serializable
data class RuleTarget(
    val kind: TargetKind = TargetKind.PROXY,
    /** id узла или группы для kind = SERVER / GROUP. */
    val id: String? = null,
) {
    val title: String
        get() = when (kind) {
            TargetKind.PROXY -> "Прокси"
            TargetKind.DIRECT -> "Напрямую"
            TargetKind.BLOCK -> "Блокировать"
            TargetKind.SERVER -> "Узел"
            TargetKind.GROUP -> "Группа"
        }

    /** Тег выхода в sing-box. */
    fun tag(): String = when (kind) {
        TargetKind.PROXY -> "proxy"
        TargetKind.DIRECT -> "direct"
        TargetKind.BLOCK -> "block"
        TargetKind.SERVER -> "server:$id"
        TargetKind.GROUP -> "group:$id"
    }

    /** Xray умеет только один прокси-выход: узлы и группы сводятся к proxy. */
    fun xrayTag(): String = when (kind) {
        TargetKind.DIRECT -> "direct"
        TargetKind.BLOCK -> "block"
        else -> "proxy"
    }

    val needsProfile: Boolean
        get() = kind == TargetKind.SERVER || kind == TargetKind.GROUP
}

@Serializable
enum class RuleDirection { DESTINATION, SOURCE }

/** Одно упорядоченное правило маршрутизации. */
@Serializable
data class RoutingRule(
    val id: String = newId(),
    val name: String = "",
    val target: RuleTarget = RuleTarget(),
    val enabled: Boolean = true,
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    /** "443", "1000-2000" или список через запятую. */
    val port: String = "",
    val direction: RuleDirection = RuleDirection.DESTINATION,
    val processNames: List<String> = emptyList(),
    val network: String = "",
    val protocols: List<String> = emptyList(),
    val invert: Boolean = false,
) {
    val hasMatcher: Boolean
        get() = domains.isNotEmpty() || ips.isNotEmpty() || processNames.isNotEmpty() ||
            protocols.isNotEmpty() || network.isNotBlank() || port.isNotBlank()

    val needsProcessMatching: Boolean
        get() = processNames.isNotEmpty()

    /**
     * Поле Xray routing; null — правило Xray выразить не может (процессы,
     * invert) или в нём нет ни одного матчера.
     */
    fun xrayRule(): JsonObject? {
        if (!enabled || needsProcessMatching || invert) return null
        val trimmedPort = port.trim()
        val matchesSomething = domains.isNotEmpty() || ips.isNotEmpty() ||
            trimmedPort.isNotEmpty() || network.isNotBlank()
        if (!matchesSomething) return null
        return buildJsonObject {
            put("type", "field")
            put("outboundTag", target.xrayTag())
            if (domains.isNotEmpty()) {
                putJsonArray("domain") { domains.forEach { add(it) } }
            }
            if (ips.isNotEmpty()) {
                putJsonArray(if (direction == RuleDirection.SOURCE) "source" else "ip") {
                    ips.forEach { add(it) }
                }
            }
            if (trimmedPort.isNotEmpty()) put("port", trimmedPort)
            if (network.isNotBlank()) put("network", network)
        }
    }

    /** Поле sing-box route rule; null — правило нечего матчить. */
    fun singboxRule(): JsonObject? {
        if (!enabled || !hasMatcher) return null
        return buildJsonObject {
            put("outbound", target.tag())
            if (domains.isNotEmpty()) {
                putJsonArray("domain") { domains.forEach { add(it) } }
            }
            if (ips.isNotEmpty()) {
                putJsonArray(if (direction == RuleDirection.SOURCE) "source_ip_cidr" else "ip_cidr") {
                    ips.forEach { add(it) }
                }
            }
            val trimmedPort = port.trim()
            if (trimmedPort.isNotEmpty()) {
                putJsonArray("port") {
                    trimmedPort.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                }
            }
            if (network.isNotBlank()) put("network", network)
            if (protocols.isNotEmpty()) {
                putJsonArray("protocol") { protocols.forEach { add(it) } }
            }
            if (processNames.isNotEmpty()) {
                putJsonArray("process_name") { processNames.forEach { add(it) } }
            }
        }
    }
}

/** Встроенные пресеты маршрутизации. */
@Serializable
enum class RoutingPreset {
    GLOBAL,
    BYPASS_LAN,
    BYPASS_RU,
    CUSTOM,
    ;

    val title: String
        get() = when (this) {
            GLOBAL -> "Всё через прокси"
            BYPASS_LAN -> "Кроме локальной сети"
            BYPASS_RU -> "Россия напрямую"
            CUSTOM -> "Свои правила"
        }

    val subtitle: String
        get() = when (this) {
            GLOBAL -> "Весь трафик идёт в туннель"
            BYPASS_LAN -> "Локальные адреса — напрямую"
            BYPASS_RU -> ".ru, .su, .рф — мимо туннеля"
            CUSTOM -> "Собственный список правил"
        }

    /** Правила, которые пресет добавляет до пользовательских. */
    fun builtInRules(): List<RoutingRule> = when (this) {
        GLOBAL, CUSTOM -> emptyList()
        BYPASS_LAN -> listOf(
            RoutingRule(
                name = "Локальная сеть",
                target = RuleTarget(TargetKind.DIRECT),
                ips = PRIVATE_CIDRS,
            ),
        )
        BYPASS_RU -> listOf(
            RoutingRule(
                name = "Российские домены",
                target = RuleTarget(TargetKind.DIRECT),
                domains = listOf("domain:ru", "domain:su", "domain:xn--p1ai"),
            ),
        )
    }

    companion object {
        val PRIVATE_CIDRS = listOf(
            "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.168.0.0/16", "198.18.0.0/15",
            "::1/128", "fc00::/7", "fe80::/10",
        )
    }
}

/** Откуда качать geoip.dat / geosite.dat для правил geosite:/geoip:. */
@Serializable
enum class GeoAssetSource {
    LOYALSOILDIER,
    RUNETFREEDOM,
    V2FLY,
    ;

    val title: String
        get() = when (this) {
            LOYALSOILDIER -> "Loyalsoldier"
            RUNETFREEDOM -> "runetfreedom (RU)"
            V2FLY -> "v2fly"
        }

    fun geoipUrl(): String = when (this) {
        LOYALSOILDIER -> "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
        RUNETFREEDOM -> "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat"
        V2FLY -> "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
    }

    fun geositeUrl(): String = when (this) {
        LOYALSOILDIER -> "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
        RUNETFREEDOM -> "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"
        V2FLY -> "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
    }
}

/* ------------------------------------------------------------------ */
/* Группы серверов — порт ServerGroup.swift / GroupQuery.swift          */
/* ------------------------------------------------------------------ */

@Serializable
enum class GroupKind {
    SELECTOR,
    URLTEST,
    ;

    val title: String
        get() = if (this == SELECTOR) "Ручной выбор" else "Самый быстрый"
    val subtitle: String
        get() = if (this == SELECTOR) "Всегда выбранный участник" else "Замер и выбор быстрейшего"
}

/** Критерии автоматической группы. Пустой запрос — все узлы. */
@Serializable
data class GroupQuery(
    val sourceIds: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val protocols: List<ProxyProtocol> = emptyList(),
    val nameContains: String = "",
    val excludeHidden: Boolean = true,
)

@Serializable
data class ServerGroup(
    val id: String = newId(),
    val name: String = "",
    val kind: GroupKind = GroupKind.SELECTOR,
    val memberIds: List<String> = emptyList(),
    val selectedId: String? = null,
    val query: GroupQuery? = null,
    val testUrl: String = "https://www.gstatic.com/generate_204",
    val interval: String = "3m",
    val tolerance: Int = 50,
) {
    /** Участники в порядке отображения; пропавшие узлы молча выпадают. */
    fun members(servers: List<ProxyConfig>): List<ProxyConfig> {
        val byId = servers.associateBy { it.id }
        val picked = memberIds.mapNotNull { byId[it] }
        if (query == null) return picked
        return servers.filter { server -> matches(server) }
    }

    private fun matches(server: ProxyConfig): Boolean {
        val q = query ?: return true
        if (q.sourceIds.isNotEmpty() && server.source !in q.sourceIds) return false
        if (q.protocols.isNotEmpty() && server.proto !in q.protocols) return false
        if (q.nameContains.isNotBlank() &&
            !server.name.contains(q.nameContains, ignoreCase = true)
        ) return false
        return true
    }

    fun representative(servers: List<ProxyConfig>): ProxyConfig? {
        val members = members(servers)
        val chosen = members.firstOrNull { it.id == selectedId } ?: members.firstOrNull() ?: return null
        return chosen.copy(id = id, name = name, balanced = members.size)
    }
}

/* ------------------------------------------------------------------ */
/* Заметки к узлам — порт NodeAnnotation.swift                          */
/* ------------------------------------------------------------------ */

@Serializable
data class NodeAnnotation(
    /** Своё имя вместо названия из подписки. */
    val nameOverride: String = "",
    val note: String = "",
    val tags: List<String> = emptyList(),
    val pinned: Boolean = false,
    val hidden: Boolean = false,
)
