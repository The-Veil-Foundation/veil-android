package dev.local.veil.platform

import dev.local.veil.core.AppSettings
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import dev.local.veil.core.TunnelOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

class DesktopServerStore : ServerStore {
    private val file = File(System.getProperty("user.home"), ".veil/servers.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override fun load(): List<ProxyConfig> = runCatching {
        if (file.exists()) json.decodeFromString(ListSerializer(ProxyConfig.serializer()), file.readText())
        else emptyList()
    }.getOrDefault(emptyList())

    override fun save(servers: List<ProxyConfig>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(ProxyConfig.serializer()), servers))
        }
    }

    override fun loadSelectedId(): String? = runCatching {
        File(System.getProperty("user.home"), ".veil/selected.txt").readText().trim().ifEmpty { null }
    }.getOrNull()

    override fun loadSubscriptions(): List<Subscription> = runCatching {
        val f = File(System.getProperty("user.home"), ".veil/subscriptions.json")
        if (f.exists()) json.decodeFromString(ListSerializer(Subscription.serializer()), f.readText()) else emptyList()
    }.getOrDefault(emptyList())

    override fun saveSubscriptions(subscriptions: List<Subscription>) {
        runCatching {
            val f = File(System.getProperty("user.home"), ".veil/subscriptions.json")
            f.parentFile?.mkdirs()
            f.writeText(json.encodeToString(ListSerializer(Subscription.serializer()), subscriptions))
        }
    }

    override fun saveSelectedId(id: String?) {
        runCatching {
            val f = File(System.getProperty("user.home"), ".veil/selected.txt")
            f.parentFile?.mkdirs()
            f.writeText(id ?: "")
        }
    }
}

/** Десктопное ядро пока не подключено — нужен sidecar-процесс xray. */
class DesktopVpnController : VpnController {
    private val _status = MutableStateFlow(VpnStatus())
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()
    override val coreAvailable: Boolean = false
    override val coreVersion: String? = null

    override suspend fun probe(server: ProxyConfig, options: TunnelOptions): Boolean = true

    override suspend fun connect(server: ProxyConfig, options: TunnelOptions): Result<Unit> =
        Result.failure(UnsupportedOperationException("На десктопе ядро ещё не подключено"))

    override suspend fun disconnect() {
        _status.value = VpnStatus()
    }

    override suspend fun reconnect(server: ProxyConfig, options: TunnelOptions): Result<Unit> =
        connect(server, options)

    override fun clearError() {
        _status.value = VpnStatus()
    }
}

class DesktopSettingsStore : SettingsStore {
    private val file = File(System.getProperty("user.home"), ".veil/settings.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override fun load(): AppSettings = runCatching {
        if (file.exists()) json.decodeFromString(AppSettings.serializer(), file.readText()) else AppSettings()
    }.getOrDefault(AppSettings())

    override fun save(settings: AppSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(AppSettings.serializer(), settings))
        }
    }
}

/** На десктопе раздельного туннелирования по приложениям нет. */
class DesktopPackageLister : PackageLister {
    override suspend fun installedApps(): List<AppEntry> = emptyList()
}

class DesktopPinger : Pinger {
    override suspend fun ping(server: ProxyConfig, timeoutMs: Int): Int? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val started = System.nanoTime()
            runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress(server.address, server.port), timeoutMs) }
                ((System.nanoTime() - started) / 1_000_000L).toInt()
            }.getOrNull()
        }
}

class DesktopLogProvider : LogProvider {
    private val file = java.io.File(System.getProperty("user.home"), ".veil/xray.log")
    override val path: String get() = file.absolutePath
    override suspend fun read(): String = runCatching {
        if (!file.exists()) "На десктопе ядро не запускалось — лога нет." else file.readText()
    }.getOrElse { "Не удалось прочитать лог: ${it.message}" }
    override fun clear() {
        runCatching { file.writeText("") }
    }
}

class DesktopContainer : AppContainer {
    override val store: ServerStore = DesktopServerStore()
    override val vpn: VpnController = DesktopVpnController()
    override val logs: LogProvider = DesktopLogProvider()
    override val geoDirectory: String =
        File(System.getProperty("user.home"), ".veil/geo").apply { mkdirs() }.absolutePath
    override val subscriptions: SubscriptionFetcher = DesktopSubscriptionFetcher()
    override val settings: SettingsStore = DesktopSettingsStore()
    override val packages: PackageLister = DesktopPackageLister()
    override val pinger: Pinger = DesktopPinger()
}
