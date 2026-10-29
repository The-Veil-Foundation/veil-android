package dev.local.veil.core

import kotlinx.serialization.Serializable

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
    /** Pitch Black: чистый чёрный фон для OLED. По умолчанию выключен. */
    val pitchBlack: Boolean = false,
) {
    fun toTunnelOptions(logFile: String? = null) = TunnelOptions(
        mtu = mtu,
        ipv6 = ipv6,
        logLevel = logLevel,
        logFile = logFile,
        dnsServers = dns,
        fragment = fragment,
        bypassRu = bypassRu,
    )
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
)
