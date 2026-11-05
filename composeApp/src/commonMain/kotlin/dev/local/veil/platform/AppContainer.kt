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
    /** Текущая скорость в байтах в секунду — считается по дельте счётчиков. */
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

    /** Приложение на экране: в фоне таймеры и счётчики не крутим. */
    val foreground: StateFlow<Boolean>

    /** Версия ядра, если оно собрано в APK. */
    val coreVersion: String?
    val coreAvailable: Boolean
    /**
     * Проверяет узел по-настоящему: поднимает ядро с локальным SOCKS и делает
     * запрос через него. TCP-пинг ловит только живые порты, а здесь проверяется
     * и рукопожатие, и авторизация на сервере.
     */
    suspend fun probe(server: ProxyConfig, options: TunnelOptions): Boolean

    /**
     * Реальная задержка: запрос через ядро и сервер, по уже открытому
     * соединению — то есть чистый RTT пути «телефон → сервер → интернет».
     * null — узел не отвечает или ядра нет.
     */
    suspend fun realPing(server: ProxyConfig, options: TunnelOptions, timeoutMs: Int = 4000): Int? = null

    /**
     * Реальная задержка для многих узлов сразу. Результат по каждому узлу
     * приходит в [report] по мере готовности. По умолчанию — по одному.
     */
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

/**
 * Скачивание подписки по URL. Только нативно: панели не отдают CORS,
 * а часть из них (Remnawave) требует HWID в заголовках.
 */
/** Лимиты тарифа из заголовка `subscription-userinfo`. */
data class SubscriptionUsage(
    val upload: Long = 0,
    val download: Long = 0,
    val total: Long = 0,
    val expireAt: Long = 0,
) {
    val used: Long get() = upload + download
    val remaining: Long get() = if (total > 0) (total - used).coerceAtLeast(0) else 0
}

/** Тело подписки плюс всё, что панель рассказала о тарифе. */
data class SubscriptionFetch(
    val body: String,
    val usage: SubscriptionUsage? = null,
    val title: String = "",
    val description: String = "",
    val supportUrl: String = "",
    val webUrl: String = "",
)

interface SubscriptionFetcher {
    /**
     * [userAgent] — свой UA источника; null или пусто — общий [userAgent].
     * [hwid] — что слать в `X-Hwid`: null — не слать вовсе.
     */
    suspend fun fetch(url: String, userAgent: String? = null, hwid: String? = deviceId): Result<SubscriptionFetch>
    /** Идентификатор устройства для панелей (HWID), 16 hex-символов как на macOS. */
    val deviceId: String
    /** Выпускает новый HWID: для панели это новое устройство. */
    fun regenerateDeviceId(): String
    /** Ставит HWID, выданный провайдером, как есть (только без пробелов по краям). */
    fun setDeviceId(raw: String): String?
    /**
     * Панели отдают разный формат в зависимости от клиента: список ссылок
     * или полный конфиг с маршрутами. Выбор UA — это выбор формата.
     */
    var userAgent: String
}

/** geoip.dat / geosite.dat для правил geosite:/geoip:. */
interface GeoService {
    val directory: String
    val hasAssets: Boolean
    /** [customGeoip]/[customGeosite] — адреса для [GeoAssetSource.CUSTOM]. */
    suspend fun download(source: GeoAssetSource, customGeoip: String = "", customGeosite: String = ""): Result<Unit>
}

/** Доступ к логам ядра из интерфейса. */
interface LogProvider {
    /** Путь к файлу — показываем в настройках, чтобы можно было достать вручную. */
    val path: String
    suspend fun read(): String
    fun clear()
}

/** Релиз с GitHub, который можно поставить поверх текущей версии. */
data class AppRelease(
    val version: String,
    val notes: String,
    val downloadUrl: String,
    val pageUrl: String,
)

/** Обновления по воздуху из GitHub Releases. */
interface UpdateService {
    val currentVersion: String
    /** null — обновлений нет (или релизов ещё не публиковали). */
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

    /** Перенос на другие устройства и «Поделиться»; по умолчанию недоступен. */
    val transfer: TransferService get() = NoTransferService
}
