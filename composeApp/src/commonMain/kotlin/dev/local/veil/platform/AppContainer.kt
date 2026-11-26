package dev.local.veil.platform

import dev.local.veil.core.AppSettings
import dev.local.veil.core.GeoAssetSource
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import dev.local.veil.core.TunnelOptions
import kotlinx.coroutines.flow.StateFlow

enum class VpnPhase { DISCONNECTED, CONNECTING, CONNECTED, PAUSED, ERROR }

data class VpnStatus(
    val phase: VpnPhase = VpnPhase.DISCONNECTED,
    val message: String? = null,
    val serverName: String = "",
    val coreVersion: String? = null,
    val uplink: Long = 0,
    val downlink: Long = 0,

    val uplinkSpeed: Long = 0,
    val downlinkSpeed: Long = 0,
    val connectedSince: Long = 0,
) {
    val isConnected: Boolean get() = phase == VpnPhase.CONNECTED
    val isPaused: Boolean get() = phase == VpnPhase.PAUSED
    val isBusy: Boolean get() = phase == VpnPhase.CONNECTING
}

interface VpnController {
    val status: StateFlow<VpnStatus>

    val foreground: StateFlow<Boolean>

    val coreVersion: String?
    val coreAvailable: Boolean

    suspend fun probe(server: ProxyConfig, options: TunnelOptions): Boolean

    suspend fun realPing(server: ProxyConfig, options: TunnelOptions, timeoutMs: Int = 4000): Int? = null

    suspend fun realPingBatch(
        servers: List<ProxyConfig>,
        options: TunnelOptions,
        timeoutMs: Int = 3000,
        report: suspend (id: String, ms: Int?) -> Unit,
    ) {
        servers.forEach { report(it.id, realPing(it, options, timeoutMs)) }
    }

    suspend fun connect(server: ProxyConfig, options: TunnelOptions): Result<Unit>
    suspend fun disconnect()
    suspend fun resume() {}
    suspend fun reconnect(server: ProxyConfig, options: TunnelOptions): Result<Unit>
    fun clearError()
}

interface ServerStore {
    fun load(): List<ProxyConfig>
    fun save(servers: List<ProxyConfig>)
    fun loadSelectedId(): String?
    fun saveSelectedId(id: String?)
    fun loadSubscriptions(): List<Subscription>
    fun saveSubscriptions(subscriptions: List<Subscription>)
}

interface SettingsStore {
    fun load(): AppSettings
    fun save(settings: AppSettings)
}

data class SubscriptionUsage(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expireAt: Long = 0,
) {
    val used: Long get() = upload + download
    val remaining: Long get() = if (total > 0) (total - used).coerceAtLeast(0) else 0
}

data class SubscriptionFetch(
    val body: String,
    val usage: SubscriptionUsage? = null,
    val title: String = "",
    val description: String = "",
    val supportUrl: String = "",
    val webUrl: String = "",
)

interface SubscriptionFetcher {

    suspend fun fetch(url: String, userAgent: String? = null, hwid: String? = deviceId): Result<SubscriptionFetch>

    val deviceId: String

    fun regenerateDeviceId(): String

    fun setDeviceId(raw: String): String?

    var userAgent: String
}

data class GeoDownloadProgress(val file: String, val bytes: Long, val total: Long?)

interface GeoService {
    val directory: String
    val hasAssets: Boolean

    suspend fun download(source: GeoAssetSource, customGeoip: String = "", customGeosite: String = ""): Result<Unit>
    suspend fun downloadWithProgress(source: GeoAssetSource, customGeoip: String, customGeosite: String,
        onProgress: (GeoDownloadProgress) -> Unit): Result<Unit> = download(source, customGeoip, customGeosite)
}

interface LogProvider {

    val path: String
    suspend fun read(): String
    fun clear()

    suspend fun readChecks(): String = ""
    fun clearChecks() {}
}

data class AppRelease(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val pageUrl: String,
)

interface UpdateService {
    val currentVersion: String

    suspend fun latest(): Result<AppRelease?>
    suspend fun download(release: AppRelease, onProgress: (Float) -> Unit): Result<String>
    fun install(path: String): Result<Unit>
    fun openSource(): Unit
}

interface AppContainer {
    val vpn: VpnController
    val logs: LogProvider
    val update: UpdateService
    val store: ServerStore
    val settings: SettingsStore
    val geoDirectory: String
    val geo: GeoService
    val subscriptions: SubscriptionFetcher
    val packages: PackageLister
    val pinger: Pinger

    val sharing: InternetSharing? get() = null

    val openNotificationSettings: (() -> Unit)? get() = null

    val transfer: TransferService get() = NoTransferService

    fun checkConfig(engine: dev.local.veil.core.CoreEngine, configJson: String): Result<Unit>? = null
}
