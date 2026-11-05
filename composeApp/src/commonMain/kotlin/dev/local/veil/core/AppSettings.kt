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

/**
 * Узел можно отдать sing-box отдельным выходом. XHTTP sing-box не умеет, а
 * WireGuard наш сборщик выходов не рендерит — такие узлы в граф не попадают,
 * иначе трафик ушёл бы мимо туннеля.
 */
val ProxyConfig.singboxCompatible: Boolean
    get() = proto != ProxyProtocol.WIREGUARD && network != TransportNetwork.XHTTP

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
    /** Версия схемы: одноразовые миграции настроек. */
    val settingsVersion: Int = 1,
    val coreEngine: CoreEngine = CoreEngine.AUTO,
    val mtu: Int = 1500,
    val ipv6: Boolean = true,
    val dns: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    /** Свой User-Agent подписки; пусто — [AppVersion.USER_AGENT] текущей версии. */
    val userAgent: String = "",
    /** Слать ли HWID (`X-Hwid`) панелям подписок. Источник может переопределить. */
    val sendHwid: Boolean = true,
    val splitMode: SplitMode = SplitMode.OFF,
    val splitPackages: Set<String> = emptySet(),
    val liveNotification: Boolean = true,
    /** 0 — только вручную. */
    val autoRefreshHours: Int = 0,
    val logLevel: String = "warning",
    /** Дробление TLS ClientHello — обход DPI, который режет рукопожатие. */
    val fragment: Boolean = false,
    /**
     * Устаревший флаг «российские домены напрямую». Его заменил пресет
     * [RoutingPreset.BYPASS_RU]; значение читается только для миграции.
     */
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
    val blockAds: Boolean = false,
    val groups: List<ServerGroup> = emptyList(),
    val dnsSettings: DnsSettings = DnsSettings(),
    /** Заметки, теги и скрытые узлы — по id. */
    val annotations: Map<String, NodeAnnotation> = emptyMap(),
    /** Откуда скачивать geoip.dat / geosite.dat. */
    val geoSource: GeoAssetSource = GeoAssetSource.LOYALSOILDIER,
    /** Свои адреса баз для [GeoAssetSource.CUSTOM]. */
    val customGeoipUrl: String = "",
    val customGeositeUrl: String = "",
    /**
     * Rule-set, добавленные руками. Те, что следуют из `geosite:`/`geoip:`,
     * выводятся при сборке и не хранятся.
     */
    val ruleSets: List<RuleSetRef> = emptyList(),
    /** Выбранные готовые списки доменов и подсетей. */
    val communityLists: List<String> = emptyList(),
    /** Куда отправлять совпадения из готовых списков. */
    val communityListTarget: RuleTarget = RuleTarget.Direct,
) {
    /**
     * Упорядоченные правила для ядра, как `effectiveRoutingRules` на macOS.
     *
     * Свои правила действуют при любом пресете. Они стоят между охраной,
     * которая должна победить, и страновыми правилами пресета, которые не
     * должны. Исключение — правило, которое само называет адреса или домены:
     * оно идёт раньше охраны, иначе «172.16.4.10 через этот узел» не сработало
     * бы никогда.
     */
    fun effectiveRoutingRules(communityRules: List<RoutingRule> = emptyList()): List<RoutingRule> {
        val addressed = customRules.filter { it.namesItsOwnDestinations }
        val rest = customRules.filter { !it.namesItsOwnDestinations }
        return addressed +
            routingPreset.guardRules(blockAds) +
            rest +
            communityRules +
            routingPreset.presetRules()
    }

    /**
     * На macOS TUN не пускает LAN в туннель (`route_exclude_address`), а правило,
     * которое само называет адреса, туда пробивается. VpnService заводит в TUN
     * всё, поэтому то же исключение делает ядро: сразу после адресных правил
     * пользователя локальные диапазоны уходят напрямую.
     */
    private fun withTunnelExcludes(rules: List<RoutingRule>): List<RoutingRule> {
        val addressed = customRules.count { it.namesItsOwnDestinations }
        val head = rules.take(addressed)
        return head + TUNNEL_EXCLUDE + rules.drop(addressed)
    }

    /** User-Agent, который реально уходит в запросах подписки. */
    val effectiveUserAgent: String
        get() = effectiveUserAgent(userAgent)

    /** Переносит устаревшие поля в их нынешний вид. */
    fun migrated(): AppSettings {
        // Старые сборки сохраняли значение по умолчанию как есть — оно не
        // выбор пользователя и должно следовать за версией.
        if (userAgent in LEGACY_DEFAULT_USER_AGENTS) return copy(userAgent = "").migrated()
        // До v2 готовые списки по умолчанию шли в прокси. Единственный
        // оставшийся список — «Россия внутри», ему нужен Direct.
        if (settingsVersion < 2) {
            return copy(
                settingsVersion = 2,
                communityListTarget = if (communityListTarget == RuleTarget.Proxy) RuleTarget.Direct
                else communityListTarget,
            ).migrated()
        }
        if (!bypassRu) return this
        val preset = when (routingPreset) {
            RoutingPreset.GLOBAL, RoutingPreset.BYPASS_LAN -> RoutingPreset.BYPASS_RU
            else -> routingPreset
        }
        return copy(bypassRu = false, routingPreset = preset)
    }

    /**
     * То, из чего собрано работающее ядро. Сравнение с этим отпечатком говорит,
     * изменит ли что-то переподключение.
     */
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
            // Ядро выбирается автоматически по протоколу; ручного выбора в UI нет.
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

/** Значения User-Agent, которые прежние сборки записывали по умолчанию. */
val LEGACY_DEFAULT_USER_AGENTS = setOf("Veil/0.1")

/** Пустое или прежнее значение по умолчанию — User-Agent текущей версии. */
fun effectiveUserAgent(stored: String?): String {
    val value = stored?.trim().orEmpty()
    return if (value.isEmpty() || value in LEGACY_DEFAULT_USER_AGENTS) dev.local.veil.AppVersion.USER_AGENT else value
}

/** Диапазоны, которые TUN на macOS держит вне туннеля. */
private val TUNNEL_EXCLUDE = RoutingRule(
    id = "tun:exclude",
    name = "LAN outside the tunnel",
    target = RuleTarget.Direct,
    ips = listOf(
        "192.168.0.0/16", "10.0.0.0/8", "172.16.0.0/12", "169.254.0.0/16",
        "224.0.0.0/4", "255.255.255.255/32", "fe80::/10", "ff00::/8",
    ),
)

/** Что правка маршрутизации может изменить в конфиге работающего ядра. */
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
    /** Своё имя источника; пусто — название от панели. */
    val name: String = "",
    /** Закреплённые источники стоят выше остальных. */
    val pinned: Boolean = false,
    /** Серверы скрытого источника не показываются в списке серверов. */
    val hidden: Boolean = false,
    /** Обновлять ли источник автоматически вместе с остальными. */
    val autoUpdate: Boolean = true,
    /** Свой User-Agent для этого источника; пусто — общий. */
    val userAgent: String = "",
    /** Слать ли HWID этой панели; null — как в общих настройках. */
    val sendHwid: Boolean? = null,
    /** Свой HWID для этой панели; пусто — общий идентификатор устройства. */
    val hwid: String = "",
    /** Формат последнего ответа панели — для экрана источника. */
    val lastFormat: String = "",
    /** Что из последнего ответа не удалось использовать. */
    val skipped: List<String> = emptyList(),
) {
    val host: String
        get() = url.substringAfter("://").substringBefore('/').ifBlank { url.take(24) }

    /** Имя для интерфейса: своё, от панели или адрес. */
    val displayName: String
        get() = name.ifBlank { title }.ifBlank { host }
}
