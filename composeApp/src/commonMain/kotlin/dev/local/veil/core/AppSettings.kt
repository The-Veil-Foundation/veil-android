package dev.local.veil.core

import kotlinx.serialization.Serializable

/** Стиль разметки: iOS-лэйаут при сохранении Material-компонентов. */
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

/** Язык интерфейса. */
@Serializable
enum class AppLanguage {
    /** Как в системе: русский для ru, иначе английский. */
    SYSTEM,
    RU,
    EN,
    ;

    /** Подпись в списке выбора — на самом языке. */
    val nativeLabel: String
        get() = when (this) {
            SYSTEM -> "Системный"
            RU -> "Русский"
            EN -> "English"
        }
}

/** Тема оформления: системная, светлая или тёмная. */
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

/** Ядро прокси: авто, Xray или sing-box. */
@Serializable
enum class CoreEngine {
    /** Само выбирает ядро по протоколу узла. */
    AUTO,
    XRAY,
    SING_BOX,
    ;

    /** Сводит режим к конкретному ядру для этого узла. */
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

/**
 * Узел умеет работать на Xray: протокол поддерживается. XHTTP тоже остаётся
 * за Xray — в sing-box его нет.
 */
val ProxyConfig.xrayCompatible: Boolean
    get() = proto.xraySupported

/** Как приложения распределяются между туннелем и обычной сетью. */
@Serializable
enum class SplitMode {
    /** Все приложения в туннеле. */
    OFF,

    /** В туннеле все, кроме выбранных. */
    EXCLUDE,

    /** В туннеле только выбранные. */
    INCLUDE,
}

@Serializable
data class AppSettings(
    val coreEngine: CoreEngine = CoreEngine.AUTO,
    val mtu: Int = 1500,
    val ipv6: Boolean = true,
    val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val userAgent: String = "Veil/0.1",
    val splitMode: SplitMode = SplitMode.OFF,
    val splitPackages: Set<String> = emptySet(),
    val liveNotification: Boolean = true,
    /** 0 — только вручную. */
    val autoRefreshHours: Int = 0,
    val logLevel: String = "warning",
    /** Дробление TLS ClientHello — обход DPI, который режет рукопожатие. */
    val fragment: Boolean = false,
    /** Российские домены — напрямую, мимо туннеля. */
    val bypassRu: Boolean = false,
    /** Язык интерфейса: как в системе по умолчанию. */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /** Тема: системная по умолчанию. */
    val appearance: Appearance = Appearance.SYSTEM,
    /** Pitch Black: чистый чёрный фон для OLED. По умолчанию выключен. */
    val pitchBlack: Boolean = false,
    /** Подключаться к выбранному узлу при запуске приложения. */
    val autoConnect: Boolean = false,
    /** Пресет маршрутизации: что идёт в туннель, а что напрямую. */
    val routingPreset: RoutingPreset = RoutingPreset.BYPASS_LAN,
    val customRules: List<RoutingRule> = emptyList(),
    val blockAds: Boolean = true,
    val groups: List<ServerGroup> = emptyList(),
    val dnsSettings: DnsSettings = DnsSettings(),
    /** Заметки, теги и скрытые узлы — по id. */
    val annotations: Map<String, NodeAnnotation> = emptyMap(),
    /** Откуда скачивать geoip.dat / geosite.dat. */
    val geoSource: GeoAssetSource = GeoAssetSource.LOYALSOILDIER,
    /** Выбранные готовые списки доменов и подсетей. */
    val communityLists: List<String> = emptyList(),
    /** Куда отправлять совпадения из готовых списков. */
    val communityListTarget: RuleTarget = RuleTarget(TargetKind.PROXY),
) {
    fun toTunnelOptions(
        logFile: String? = null,
        servers: List<ProxyConfig> = emptyList(),
        group: ServerGroup? = null,
    ): TunnelOptions {
        val activeRules = buildList {
            if (routingPreset != RoutingPreset.CUSTOM) addAll(routingPreset.builtInRules())
            addAll(customRules)
        }
        val members = group?.members(servers).orEmpty()
        return TunnelOptions(
            mtu = mtu,
            ipv6 = ipv6,
            logLevel = logLevel,
            logFile = logFile,
            dnsServers = dns,
            fragment = fragment,
            bypassRu = bypassRu,
            // Ядро выбирается автоматически по протоколу; ручного выбора в UI нет.
            coreEngine = CoreEngine.AUTO,
            rules = activeRules,
            blockAds = blockAds,
            dnsSettings = dnsSettings,
            balancerMembers = if (group?.kind == GroupKind.URLTEST) members else emptyList(),
        )
    }
}

@Serializable
data class Subscription(
    val url: String,
    val title: String = "",
    val lastFetched: Long = 0L,
    val nodeCount: Int = 0,
    /** Сколько уже израсходовано, байты (upload + download с панели). */
    val usedBytes: Long = 0L,
    /** Лимит тарифа в байтах, 0 — без лимита. */
    val totalBytes: Long = 0L,
    /** Срок действия подписки, epoch-секунды. */
    val expireAt: Long = 0L,
    /** Описание/анонс от панели (заголовок announce). */
    val description: String = "",
    /** Страница поддержки и сайт подписки. */
    val supportUrl: String = "",
    val webUrl: String = "",
)
