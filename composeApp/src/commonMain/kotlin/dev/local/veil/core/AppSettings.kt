package dev.local.veil.core

import kotlinx.serialization.Serializable

@Serializable
enum class AppStyle {
    MATERIAL,
    IOS,
    ;

    val label: String
        get() = when (this) {
            MATERIAL -> "Material 3"
            IOS -> "iOS"
        }
}

@Serializable
enum class AppLanguage {

    SYSTEM,
    RU,
    EN,
    ZH,
    ES,
    HI,
    AR,
    FR,
    PT,
    DE,
    JA,
    ID,
    TR,
    ;

    val nativeLabel: String
        get() = when (this) {
            SYSTEM -> "Системный"
            RU -> "Русский"
            EN -> "English"
            ZH -> "中文"
            ES -> "Español"
            HI -> "हिन्दी"
            AR -> "العربية"
            FR -> "Français"
            PT -> "Português"
            DE -> "Deutsch"
            JA -> "日本語"
            ID -> "Bahasa Indonesia"
            TR -> "Türkçe"
        }

    companion object {
        fun fromCode(code: String): AppLanguage = entries.firstOrNull {
            it != SYSTEM && it.name.equals(code.substringBefore('-').substringBefore('_'), ignoreCase = true)
        } ?: EN
    }
}

@Serializable
enum class Appearance {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    val label: String
        get() = when (this) {
            SYSTEM -> "Системная"
            LIGHT -> "Светлая"
            DARK -> "Тёмная"
        }
}

@Serializable
enum class CoreEngine {

    AUTO,
    XRAY,
    SING_BOX,
    ;

    fun resolve(server: ProxyConfig): CoreEngine = when (this) {
        AUTO -> if (server.xrayCompatible) XRAY else SING_BOX
        else -> this
    }

    val label: String
        get() = when (this) {
            AUTO -> "Авто"
            XRAY -> "Xray"
            SING_BOX -> "sing-box"
        }
}

val ProxyConfig.xrayCompatible: Boolean
    get() = proto.xraySupported

val ProxyConfig.singboxCompatible: Boolean
    get() = proto != ProxyProtocol.WIREGUARD && network != TransportNetwork.XHTTP

@Serializable
enum class SplitMode {

    OFF,

    EXCLUDE,

    INCLUDE,
}

@Serializable
data class AppSettings(

    val settingsVersion: Int = 1,
    val coreEngine: CoreEngine = CoreEngine.AUTO,
    val mtu: Int = 1500,
    val ipv6: Boolean = true,
    val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),

    val userAgent: String = "",

    val sendHwid: Boolean = true,
    val splitMode: SplitMode = SplitMode.OFF,
    val splitPackages: Set<String> = emptySet(),
    val liveNotification: Boolean = true,

    val showSourcesTab: Boolean = false,
    val showSharingTab: Boolean = true,

    val autoRefreshHours: Int = 0,
    val logLevel: String = "warning",

    val fragment: Boolean = false,

    val bypassRu: Boolean = false,

    val language: AppLanguage = AppLanguage.SYSTEM,

    val appearance: Appearance = Appearance.SYSTEM,

    val dockActiveLabelOnly: Boolean = true,
    val swipeTabs: Boolean = true,
    val animateTabTransitions: Boolean = true,

    val pitchBlack: Boolean = false,

    val autoConnect: Boolean = false,

    val routingPreset: RoutingPreset = RoutingPreset.BYPASS_LAN,
    val customRules: List<RoutingRule> = emptyList(),
    val blockAds: Boolean = false,
    val groups: List<ServerGroup> = emptyList(),
    val dnsSettings: DnsSettings = DnsSettings(),

    val annotations: Map<String, NodeAnnotation> = emptyMap(),

    val geoSource: GeoAssetSource = GeoAssetSource.LOYALSOILDIER,

    val customGeoipUrl: String = "",
    val customGeositeUrl: String = "",

    val ruleSets: List<RuleSetRef> = emptyList(),

    val communityLists: List<String> = emptyList(),

    val communityListTarget: RuleTarget = RuleTarget.Direct,
) {

    fun effectiveRoutingRules(communityRules: List<RoutingRule> = emptyList()): List<RoutingRule> {
        val addressed = customRules.filter { it.namesItsOwnDestinations }
        val rest = customRules.filter { !it.namesItsOwnDestinations }
        return addressed +
            routingPreset.guardRules(blockAds) +
            rest +
            communityRules +
            routingPreset.presetRules()
    }

    private fun withTunnelExcludes(rules: List<RoutingRule>): List<RoutingRule> {
        val addressed = customRules.count { it.namesItsOwnDestinations }
        val head = rules.take(addressed)
        return head + TUNNEL_EXCLUDE + rules.drop(addressed)
    }

    val needsGeoAssets: Boolean
        get() = blockAds || routingPreset.needsGeoAssets || customRules.any { rule ->
            rule.enabled && (rule.domains.any { it.startsWith("geosite:", ignoreCase = true) } ||
                rule.ips.any { it.startsWith("geoip:", ignoreCase = true) })
        }

    val effectiveUserAgent: String
        get() = effectiveUserAgent(userAgent)

    fun migrated(): AppSettings {

        if (userAgent in LEGACY_DEFAULT_USER_AGENTS) return copy(userAgent = "").migrated()

        if (settingsVersion < 2) {
            return copy(
                settingsVersion = 2,
                communityListTarget = if (communityListTarget == RuleTarget.Proxy) RuleTarget.Direct
                else communityListTarget,
            ).migrated()
        }

        if (bypassRu) {
            val preset = when (routingPreset) {
                RoutingPreset.GLOBAL, RoutingPreset.BYPASS_LAN -> RoutingPreset.BYPASS_RU
                else -> routingPreset
            }
            return copy(bypassRu = false, routingPreset = preset).migrated()
        }
        return when (routingPreset) {

            RoutingPreset.BYPASS_RU -> copy(
                routingPreset = RoutingPreset.BYPASS_LAN,
                communityLists = if (RU_INSIDE_LIST in communityLists) communityLists
                else communityLists + RU_INSIDE_LIST,
                communityListTarget = if (communityLists.isEmpty()) RuleTarget.Direct else communityListTarget,
            )
            RoutingPreset.BYPASS_CHINA -> copy(routingPreset = RoutingPreset.BYPASS_LAN)
            else -> this
        }
    }

    val routingFingerprint: RoutingFingerprint
        get() = RoutingFingerprint(
            preset = routingPreset,
            blockAds = blockAds,
            customRules = customRules,
            groups = groups,
            ruleSets = ruleSets,
            communityLists = communityLists,
            communityListTarget = communityListTarget,
            dns = dnsSettings,
        )

    fun toTunnelOptions(
        logFile: String? = null,
        servers: List<ProxyConfig> = emptyList(),
        group: ServerGroup? = null,
        communityRules: List<RoutingRule> = emptyList(),
        geoAvailable: Boolean = false,
        clashSecret: String = "",
    ): TunnelOptions {
        val members = group?.members(servers).orEmpty()
        return TunnelOptions(
            mtu = mtu,
            ipv6 = ipv6,
            logLevel = logLevel,
            logFile = logFile,
            dnsServers = dns,
            fragment = fragment,

            coreEngine = CoreEngine.AUTO,
            rules = withTunnelExcludes(effectiveRoutingRules(communityRules)),
            dnsSettings = dnsSettings,
            balancerMembers = if (group?.kind == GroupKind.URLTEST) members else emptyList(),
            geoAvailable = geoAvailable,
            servers = servers,
            groups = groups,
            ruleSets = ruleSets,
            clashSecret = clashSecret,
        )
    }
}

const val RU_INSIDE_LIST = "ru-inside"

val LEGACY_DEFAULT_USER_AGENTS = setOf("Veil/0.1")

fun effectiveUserAgent(stored: String?): String {
    val value = stored?.trim().orEmpty()
    return if (value.isEmpty() || value in LEGACY_DEFAULT_USER_AGENTS) dev.local.veil.AppVersion.USER_AGENT else value
}

private val TUNNEL_EXCLUDE = RoutingRule(
    id = "tun:exclude",
    name = "LAN outside the tunnel",
    target = RuleTarget.Direct,
    ips = listOf(
        "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "169.254.0.0/16",
        "224.0.0.0/4", "255.255.255.255/32", "fe80::/10", "ff00::/8",
    ),
)

data class RoutingFingerprint(
    val preset: RoutingPreset,
    val blockAds: Boolean,
    val customRules: List<RoutingRule>,
    val groups: List<ServerGroup>,
    val ruleSets: List<RuleSetRef>,
    val communityLists: List<String>,
    val communityListTarget: RuleTarget,
    val dns: DnsSettings,
)

@Serializable
data class Subscription(
    val url: String,
    val title: String = "",
    val lastFetched: Long = 0L,
    val nodeCount: Int = 0,

    val usedBytes: Long = 0L,

    val totalBytes: Long = 0L,

    val expireAt: Long = 0L,

    val description: String = "",

    val supportUrl: String = "",
    val webUrl: String = "",

    val name: String = "",

    val pinned: Boolean = false,

    val hidden: Boolean = false,

    val autoUpdate: Boolean = true,

    val userAgent: String = "",

    val sendHwid: Boolean? = null,

    val hwid: String = "",

    val lastFormat: String = "",

    val skipped: List<String> = emptyList(),
) {
    val host: String
        get() = url.substringAfter("://").substringBefore('/').ifBlank { url.take(24) }

    val displayName: String
        get() = name.ifBlank { title }.ifBlank { host }
}
