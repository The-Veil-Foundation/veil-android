package dev.local.veil.platform

import dev.local.veil.core.AppSettings
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import dev.local.veil.core.TunnelOptions
import kotlinx.coroutines.flow.StateFlow

enum class VpnPhase { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

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
    val isBusy: Boolean get() = phase == VpnPhase.CONNECTING
}

interface VpnController {
    val status: StateFlow<VpnStatus>

    /** Версия ядра, если оно собрано в APK. */
    val coreVersion: String?
    val coreAvailable: Boolean
    /**
     * Проверяет узел по-настоящему: поднимает ядро с локальным SOCKS и делает
     * запрос через него. TCP-пинг ловит только живые порты, а здесь проверяется
     * и рукопожатие, и авторизация на сервере.
     */
    suspend fun probe(server: ProxyConfig, options: TunnelOptions): Boolean

    suspend fun connect(server: ProxyConfig, options: TunnelOptions): Result<Unit>
    suspend fun disconnect()
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
)

interface SubscriptionFetcher {
    suspend fun fetch(url: String): Result<SubscriptionFetch>
    /** Стабильный идентификатор устройства, если панель его спрашивает. */
    val deviceId: String
    /**
     * Панели отдают разный формат в зависимости от клиента: список ссылок
     * или полный конфиг с маршрутами. Выбор UA — это выбор формата.
     */
    var userAgent: String
}

/** Доступ к логам ядра из интерфейса. */
interface LogProvider {
    /** Путь к файлу — показываем в настройках, чтобы можно было достать вручную. */
    val path: String
    suspend fun read(): String
    fun clear()
}

interface AppContainer {
    val vpn: VpnController
    val logs: LogProvider
    val store: ServerStore
    val settings: SettingsStore
    val geoDirectory: String
    val subscriptions: SubscriptionFetcher
    val packages: PackageLister
    val pinger: Pinger
}
