package dev.local.veil.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/* ------------------------------------------------------------------ */
/* Маршрутизация — порт Routing.swift из veil-macos                     */
/* ------------------------------------------------------------------ */

/**
 * Куда правило отправляет трафик.
 *
 * SERVER и GROUP позволяют пустить два приложения через два разных узла
 * одновременно: правило называет конкретный узел или группу, а не «прокси».
 */
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
            TargetKind.PROXY -> "Proxy"
            TargetKind.DIRECT -> "Direct"
            TargetKind.BLOCK -> "Block"
            TargetKind.SERVER -> "Server"
            TargetKind.GROUP -> "Group"
        }

    /** Тег выхода в профиле sing-box. */
    fun tag(): String = when (kind) {
        TargetKind.PROXY -> ProfileTags.DEFAULT_SELECTOR
        TargetKind.DIRECT -> ProfileTags.DIRECT
        TargetKind.BLOCK -> ProfileTags.BLOCK
        TargetKind.SERVER -> ProfileTags.server(id.orEmpty())
        TargetKind.GROUP -> ProfileTags.group(id.orEmpty())
    }

    /** Выход, если узлы и группы сведены к одному прокси. */
    fun xrayTag(): String = when (kind) {
        TargetKind.DIRECT -> "direct"
        TargetKind.BLOCK -> "block"
        else -> "proxy"
    }

    /** Цель называет то, что строится только полным профилем. */
    val needsProfile: Boolean
        get() = (kind == TargetKind.SERVER || kind == TargetKind.GROUP) && !id.isNullOrBlank()

    val isDirectOrBlock: Boolean
        get() = kind == TargetKind.DIRECT || kind == TargetKind.BLOCK

    companion object {
        val Proxy = RuleTarget(TargetKind.PROXY)
        val Direct = RuleTarget(TargetKind.DIRECT)
        val Block = RuleTarget(TargetKind.BLOCK)
        fun server(id: String) = RuleTarget(TargetKind.SERVER, id)
        fun group(id: String) = RuleTarget(TargetKind.GROUP, id)
    }
}

/** К какой стороне соединения применяются адресные матчеры правила. */
@Serializable
enum class RuleDirection { DESTINATION, SOURCE }

/**
 * Одно упорядоченное правило маршрутизации.
 *
 * Правило срабатывает, когда совпала *каждая* заполненная группа матчеров, а
 * группа — когда совпал *любой* её элемент (AND из OR, как в sing-box).
 * `invert` переворачивает результат.
 *
 * `domains` и `ips` хранятся в Xray-синтаксисе (`domain:`, `keyword:`,
 * `regexp:`, `geosite:`, `geoip:`) — так говорят ссылки, панели и остальные
 * клиенты. [MatcherSyntax] раскладывает их обратно для sing-box.
 */
@Serializable
data class RoutingRule(
    val id: String = newId(),
    val name: String = "",
    val target: RuleTarget = RuleTarget(),
    val enabled: Boolean = true,
    // Адресные матчеры
    val domains: List<String> = emptyList(),
    val ips: List<String> = emptyList(),
    /** "443", "1000-2000" или список через запятую. */
    val port: String = "",
    val direction: RuleDirection = RuleDirection.DESTINATION,
    /**
     * Приложения. На macOS это имена процессов, на Android — имена пакетов
     * (`package_name` в sing-box). Работают только на sing-box.
     */
    val processNames: List<String> = emptyList(),
    // Дополнительные матчеры sing-box
    /** "" | "tcp" | "udp" */
    val network: String = "",
    /** Определённые сниффингом: http, tls, quic, dns, bittorrent… */
    val protocols: List<String> = emptyList(),
    /** Явные теги rule-set. */
    val ruleSets: List<String> = emptyList(),
    /** "" | "Rule" | "Global" | "Direct" */
    val clashMode: String = "",
    val invert: Boolean = false,
) {
    /** Правило не матчит ничего — сборщики его пропускают, а не ловят всё подряд. */
    val hasMatcher: Boolean
        get() = domains.isNotEmpty() || ips.isNotEmpty() || processNames.isNotEmpty() ||
            ruleSets.isNotEmpty() || protocols.isNotEmpty() || network.isNotBlank() ||
            clashMode.isNotBlank() || port.isNotBlank()

    /** Правило выполнимо только на sing-box. */
    val needsProcessMatching: Boolean
        get() = processNames.isNotEmpty()

    /**
     * Правило называет свои адреса или домены, а не приложение.
     *
     * Это решает, может ли обход локальной сети из пресета обогнать правило.
     * «Всё, что шлёт Slack» не должно утащить LAN-трафик Slack в туннель, а
     * «192.168.8.0/24 через этот узел» — как раз фраза про LAN.
     */
    val namesItsOwnDestinations: Boolean
        get() = direction == RuleDirection.DESTINATION && (domains.isNotEmpty() || ips.isNotEmpty())

    /** Обычные диапазоны адресов назначения, без `geoip:` и `ext:`. */
    val destinationCIDRs: List<String>
        get() = if (direction != RuleDirection.DESTINATION) {
            emptyList()
        } else {
            ips.filter { !it.startsWith("geoip:") && !it.startsWith("ext:") }
        }

    /**
     * Поле Xray routing; null — Xray выразить правило не может (процессы,
     * invert, только sing-box-матчеры) или в нём нет ни одного матчера.
     * Такие правила выкидываются, а не расширяются молча.
     *
     * [outboundTag] — уже разрешённый выход; [balancer] — это тег балансировщика.
     */
    fun xrayRule(
        outboundTag: String = target.xrayTag(),
        balancer: Boolean = false,
    ): JsonObject? {
        if (!enabled || needsProcessMatching || invert) return null
        val xrayPort = port.takeIf { it.isNotBlank() }?.let { MatcherSyntax.xrayPorts(it) }
        if (port.isNotBlank() && xrayPort == null) return null
        val matchesSomething = domains.isNotEmpty() || ips.isNotEmpty() ||
            xrayPort != null || network.isNotBlank()
        if (!matchesSomething) return null
        // Протоколы и rule-set Xray не понимает: правило с ними без них стало бы шире.
        if (protocols.isNotEmpty() || ruleSets.isNotEmpty() || clashMode.isNotBlank()) return null
        return buildJsonObject {
            put("type", "field")
            put(if (balancer) "balancerTag" else "outboundTag", outboundTag)
            if (domains.isNotEmpty()) {
                putJsonArray("domain") { domains.forEach { add(it) } }
            }
            if (ips.isNotEmpty()) {
                putJsonArray(if (direction == RuleDirection.SOURCE) "source" else "ip") {
                    ips.forEach { add(it) }
                }
            }
            if (xrayPort != null) {
                put(if (direction == RuleDirection.SOURCE) "sourcePort" else "port", xrayPort)
            }
            if (network.isNotBlank()) put("network", network)
        }
    }

    companion object {
        /** Есть ли среди правил такие, что называют конкретный узел или группу. */
        fun needsProfile(rules: List<RoutingRule>): Boolean =
            rules.any { it.enabled && it.target.needsProfile }
    }
}

/**
 * Встроенные пресеты, как в v2rayN/Nekoray. Каждый даёт упорядоченный список
 * правил. Пресеты с `geosite:`/`geoip:` подтягивают rule-set (sing-box) или
 * .dat-базы (Xray).
 *
 * Имена констант не меняются: по ним настройки лежат на диске.
 */
@Serializable
enum class RoutingPreset {
    GLOBAL,
    BYPASS_LAN,
    BYPASS_CHINA,
    BYPASS_RU,
    CUSTOM,
    ;

    val title: String
        get() = when (this) {
            GLOBAL -> "Global"
            BYPASS_LAN -> "Bypass LAN"
            BYPASS_CHINA -> "Bypass China"
            BYPASS_RU -> "Bypass Russia"
            CUSTOM -> "Custom"
        }

    /** Нужны ли пресету geo-категории (.dat у Xray, rule-set у sing-box). */
    val needsGeoAssets: Boolean
        get() = this == BYPASS_CHINA || this == BYPASS_RU

    /** Отправляет ли пресет частные диапазоны напрямую. */
    val bypassesLAN: Boolean
        get() = this == BYPASS_LAN || this == BYPASS_CHINA || this == BYPASS_RU

    /** Встроенные правила целиком: охрана и собственные правила пресета. */
    fun builtInRules(blockAds: Boolean): List<RoutingRule> = guardRules(blockAds) + presetRules()

    /**
     * Правила, которые должны стоять раньше пользовательских.
     *
     * Пользовательское правило обычно называет приложение, а приложение ходит и
     * в LAN, и в интернет. Если бы оно обгоняло обход частных диапазонов,
     * принтер и NAS уехали бы в туннель.
     */
    fun guardRules(blockAds: Boolean): List<RoutingRule> = buildList {
        if (blockAds) {
            add(
                RoutingRule(
                    id = "preset:block-ads",
                    name = "Block ads",
                    target = RuleTarget.Block,
                    domains = listOf("geosite:category-ads-all"),
                ),
            )
        }
        when (this@RoutingPreset) {
            GLOBAL, CUSTOM -> Unit
            BYPASS_LAN -> add(
                RoutingRule(id = "preset:lan", name = "LAN direct", target = RuleTarget.Direct, ips = PRIVATE_CIDRS),
            )
            BYPASS_CHINA, BYPASS_RU -> add(
                RoutingRule(
                    id = "preset:lan",
                    name = "LAN direct",
                    target = RuleTarget.Direct,
                    ips = PRIVATE_CIDRS + "geoip:private",
                ),
            )
        }
    }

    /** Мнение самого пресета — применяется после пользовательских правил. */
    fun presetRules(): List<RoutingRule> = when (this) {
        GLOBAL, BYPASS_LAN, CUSTOM -> emptyList()
        BYPASS_CHINA -> listOf(
            RoutingRule(
                id = "preset:cn-sites",
                name = "China sites direct",
                target = RuleTarget.Direct,
                domains = listOf("geosite:cn"),
            ),
            RoutingRule(id = "preset:cn-ips", name = "China IPs direct", target = RuleTarget.Direct, ips = listOf("geoip:cn")),
        )
        BYPASS_RU -> listOf(
            RoutingRule(
                id = "preset:ru-sites",
                name = "RU gov & category direct",
                target = RuleTarget.Direct,
                // Национальные зоны дописаны к категориям: без geo-базы на Xray
                // категории вырезаются, и пресет не должен превращаться в пустышку.
                domains = listOf(
                    "geosite:category-gov-ru",
                    "geosite:category-ru",
                    "domain:ru",
                    "domain:su",
                    "domain:xn--p1ai",
                ),
            ),
            RoutingRule(id = "preset:ru-ips", name = "RU IPs direct", target = RuleTarget.Direct, ips = listOf("geoip:ru")),
        )
    }

    companion object {
        /** То, что значит `geoip:private`, перечисленное явно. */
        val PRIVATE_CIDRS = listOf(
            "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
            "169.254.0.0/16", "::1/128", "fc00::/7", "fe80::/10",
        )
    }
}

/**
 * Откуда качать geoip.dat / geosite.dat для Xray.
 * sing-box с 1.12 .dat не читает и использует rule-set — см. [RuleSetRef].
 */
@Serializable
enum class GeoAssetSource {
    LOYALSOILDIER,
    RUNETFREEDOM,
    V2FLY,
    CUSTOM,
    ;

    val title: String
        get() = when (this) {
            LOYALSOILDIER -> "Loyalsoldier"
            RUNETFREEDOM -> "runetfreedom (RU)"
            V2FLY -> "v2fly"
            CUSTOM -> "Custom"
        }

    fun geoipUrl(custom: String = ""): String = when (this) {
        LOYALSOILDIER -> "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat"
        RUNETFREEDOM -> "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat"
        V2FLY -> "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
        CUSTOM -> custom.trim()
    }

    fun geositeUrl(custom: String = ""): String = when (this) {
        LOYALSOILDIER -> "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat"
        RUNETFREEDOM -> "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat"
        V2FLY -> "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"
        CUSTOM -> custom.trim()
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
