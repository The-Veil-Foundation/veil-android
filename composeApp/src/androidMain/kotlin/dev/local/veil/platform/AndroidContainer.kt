package dev.local.veil.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import dev.local.veil.core.TunnelOptions
import dev.local.veil.core.XrayInbound
import dev.local.veil.core.XrayConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

// HTTPS, а не HTTP: при targetSdk 28+ Android запрещает cleartext-запросы,
// и проверка падала бы на любом узле.
private const val PROBE_URL = "https://www.gstatic.com/generate_204"

class AndroidVpnController(
    private val activity: ComponentActivity,
    private val geoDirectory: String,
    private val logFile: String = "",
    private val settingsStore: SettingsStore,
) : VpnController {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _status = MutableStateFlow(VpnStatus())
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    override val coreAvailable: Boolean get() = XrayBridge.available
    override val coreVersion: String? get() = XrayBridge.version()

    private var pollJob: Job? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingPermission?.complete(result.resultCode == android.app.Activity.RESULT_OK)
            pendingPermission = null
        }

    init {
        scope.launch {
            VeilVpnService.status.collect { service ->
                _status.value = service.withCounters()
                if (service.isConnected) startPolling() else stopPolling()
            }
        }
    }

    override suspend fun connect(server: ProxyConfig, options: TunnelOptions): Result<Unit> =
        runCatching {
            val effective = if (logFile.isNotEmpty()) options.copy(logFile = logFile) else options
            val config = resolveEndpoints(XrayConfig.json(server.copy(id = server.id), effective))
            if (!coreAvailable) error("xray.aar не подключён к сборке")
            if (!server.isUsable) error("Протокол ${server.proto.wire} не поддерживается Xray")
            if (!ensurePermission()) error("Разрешение на VPN не выдано")

            // Каждая новая сессия начинается с чистого лога.
            if (logFile.isNotEmpty()) XrayBridge.prepareLog(logFile)

            val settings = settingsStore.load()
            VeilVpnService.start(
                context = activity.applicationContext,
                configJson = config,
                serverName = server.name,
                options = options,
                geoDirectory = geoDirectory,
                splitMode = settings.splitMode,
                splitPackages = settings.splitPackages,
                liveNotification = settings.liveNotification,
            )
        }

    /** Пробное ядро одно на всё приложение, поэтому проверки идут строго по очереди. */
    private val probeMutex = Mutex()

    /**
     * Поднимает второй экземпляр ядра с локальным SOCKS под одноразовым паролем,
     * отдаёт порт блоку и гасит ядро. Рабочий туннель продолжает жить.
     */
    private suspend fun <T> withProbeCore(
        server: ProxyConfig,
        options: TunnelOptions,
        block: (port: Int) -> T,
    ): T? = probeMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!coreAvailable) return@withContext null
            // Пробное ядро пишет в отдельный файл: два экземпляра в одном
            // файле мешали бы друг другу.
            val probeLog = logFile.takeIf { it.isNotEmpty() }?.let { "$it.probe" }
            val effective = options.copy(logFile = probeLog, logLevel = "debug")
            val port = 21000 + (System.nanoTime() % 500).toInt()
            // Одноразовый логин/пароль: пока идёт проверка, чужое приложение
            // на телефоне не сможет пустить трафик через этот SOCKS.
            val rnd = java.security.SecureRandom()
            val user = "p" + java.math.BigInteger(64, rnd).toString(16)
            val pass = java.math.BigInteger(128, rnd).toString(16)
            val config = resolveEndpoints(
                XrayConfig.json(server, effective, XrayInbound.LocalProxy(socks = port, http = port + 1, user = user, pass = pass)),
                dump = false,
            )
            try {
                XrayBridge.startProbe(config, geoDirectory)
                    .onFailure { android.util.Log.w("VeilXray", "пробное ядро не поднялось: ${it.message}") }
                    .getOrElse { return@withContext null }
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication() =
                        java.net.PasswordAuthentication(user, pass.toCharArray())
                })
                block(port)
            } finally {
                java.net.Authenticator.setDefault(null)
                runCatching { XrayBridge.stopProbe() }
            }
        }
    }

    override suspend fun probe(server: ProxyConfig, options: TunnelOptions): Boolean =
        // Один редкий обрыв не должен отменять переключение: вторая попытка
        // идёт по свежему соединению.
        withProbeCore(server, options) { port ->
            requestThroughSocks(port, 5000) != null || requestThroughSocks(port, 5000) != null
        } ?: false

    override suspend fun realPing(server: ProxyConfig, options: TunnelOptions, timeoutMs: Int): Int? =
        withProbeCore(server, options) { port ->
            // Первый запрос платит за старт ядра, DNS, TCP и TLS — это не пинг,
            // он только проверяет, что узел живой.
            val cold = requestThroughSocks(port, timeoutMs) ?: requestThroughSocks(port, timeoutMs)
                ?: return@withProbeCore null
            // Следующие идут по уже открытому соединению: чистый RTT пути.
            val warm = listOfNotNull(
                requestThroughSocks(port, timeoutMs),
                requestThroughSocks(port, timeoutMs),
            )
            warm.minOrNull() ?: cold
        }

    /**
     * Запрос через локальный SOCKS. Возвращает время до ответа в мс,
     * null — не получилось. Любой HTTP-ответ значит, что узел живой.
     */
    private fun requestThroughSocks(port: Int, timeoutMs: Int): Int? = runCatching {
        val proxy = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress.createUnresolved("127.0.0.1", port),
        )
        val started = System.nanoTime()
        val connection = java.net.URL(PROBE_URL).openConnection(proxy) as java.net.HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        val code = connection.responseCode
        val elapsed = ((System.nanoTime() - started) / 1_000_000L).toInt()
        // Дочитываем ответ и не вызываем disconnect(): соединение возвращается
        // в пул, и следующий замер идёт без рукопожатия.
        runCatching { (connection.errorStream ?: connection.inputStream).use { it.readBytes() } }
        if (code > 0) elapsed.coerceAtLeast(1) else null
    }.onFailure { android.util.Log.w("VeilXray", "запрос через узел не прошёл: ${it.message}") }
        .getOrNull()

    override suspend fun disconnect() {
        VeilVpnService.stop(activity.applicationContext)
    }

    override suspend fun resume() {
        VeilVpnService.togglePause(activity.applicationContext)
    }

    /**
     * Смена сервера. Если туннель уже поднят — перезапускаем только ядро,
     * TUN и его дескриптор остаются на месте (аналог iOS `reload`).
     */
    override suspend fun reconnect(server: ProxyConfig, options: TunnelOptions): Result<Unit> =
        runCatching {
            if (!coreAvailable) error("xray.aar не подключён к сборке")
            if (!server.isUsable) error("Протокол ${server.proto.wire} не поддерживается Xray")

            val effective = if (logFile.isNotEmpty()) options.copy(logFile = logFile) else options

            if (_status.value.isConnected) {
                val config = resolveEndpoints(XrayConfig.json(server, effective))
                val settings = settingsStore.load()
                VeilVpnService.reload(
                    activity.applicationContext,
                    config,
                    server.name,
                    splitMode = settings.splitMode,
                    splitPackages = settings.splitPackages,
                )
            } else {
                if (!ensurePermission()) error("Разрешение на VPN не выдано")
                val settings = settingsStore.load()
                VeilVpnService.start(
                    context = activity.applicationContext,
                    configJson = resolveEndpoints(XrayConfig.json(server, effective)),
                    serverName = server.name,
                    options = options,
                    geoDirectory = geoDirectory,
                    splitMode = settings.splitMode,
                    splitPackages = settings.splitPackages,
                    liveNotification = settings.liveNotification,
                )
            }
        }

    override fun clearError() {
        if (_status.value.phase == VpnPhase.ERROR) VeilVpnService.reset()
    }

    /**
     * Go-резолвер в Android-процессе не работает (нет /etc/resolv.conf),
     * поэтому домены узлов превращаем в IP ещё до передачи конфига ядру.
     */
    private suspend fun resolveEndpoints(configJson: String, dump: Boolean = true): String =
        withContext(Dispatchers.IO) {
        val resolved = EndpointResolver.apply(configJson)
        // Конфиг рядом с логом: по нему видно, что реально ушло в ядро.
        if (dump && logFile.isNotEmpty()) runCatching {
            val file = java.io.File(logFile)
            file.parentFile?.mkdirs()
            java.io.File(file.parentFile, "xray.config.json").writeText(resolved)
        }
        val broken = EndpointResolver.unresolvedHosts(resolved)
        if (broken.isNotEmpty()) {
            android.util.Log.e("VeilXray", "не разрешились адреса узлов: $broken")
        }
        resolved
    }

    private fun VpnStatus.withCounters(): VpnStatus =
        if (isConnected) copy(
            coreVersion = coreVersion ?: XrayBridge.version(),
            uplink = XrayBridge.uplinkTotal(),
            downlink = XrayBridge.downlinkTotal(),
        ) else copy(uplink = 0, downlink = 0)

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var lastUp = 0L
            var lastDown = 0L
            var lastAt = 0L
            while (isActive) {
                delay(1000)
                val up = XrayBridge.uplinkTotal()
                val down = XrayBridge.downlinkTotal()
                val now = System.currentTimeMillis()
                val seconds = if (lastAt == 0L) 1.0
                else ((now - lastAt) / 1000.0).coerceAtLeast(0.2)
                val upSpeed = if (lastAt == 0L) 0L
                else ((up - lastUp).coerceAtLeast(0) / seconds).toLong()
                val downSpeed = if (lastAt == 0L) 0L
                else ((down - lastDown).coerceAtLeast(0) / seconds).toLong()

                _status.update {
                    it.copy(
                        uplink = up,
                        downlink = down,
                        uplinkSpeed = upSpeed,
                        downlinkSpeed = downSpeed,
                    )
                }
                lastUp = up
                lastDown = down
                lastAt = now
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun ensurePermission(): Boolean {
        val intent = VpnService.prepare(activity) ?: return true
        val deferred = CompletableDeferred<Boolean>()
        pendingPermission = deferred
        permissionLauncher.launch(intent)
        return deferred.await()
    }
}

class AndroidServerStore(context: Context) : ServerStore {
    private val prefs = context.getSharedPreferences("veil", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val subscriptionStore = AndroidSubscriptionStore(context)

    override fun load(): List<ProxyConfig> = runCatching {
        prefs.getString(KEY_SERVERS, null)
            ?.let { json.decodeFromString(ListSerializer(ProxyConfig.serializer()), it) }
    }.getOrNull().orEmpty()

    override fun save(servers: List<ProxyConfig>) {
        prefs.edit()
            .putString(KEY_SERVERS, json.encodeToString(ListSerializer(ProxyConfig.serializer()), servers))
            .apply()
    }

    override fun loadSelectedId(): String? = prefs.getString(KEY_SELECTED, null)

    override fun saveSelectedId(id: String?) {
        prefs.edit().putString(KEY_SELECTED, id).apply()
    }

    override fun loadSubscriptions(): List<Subscription> = subscriptionStore.load()

    override fun saveSubscriptions(subscriptions: List<Subscription>) =
        subscriptionStore.save(subscriptions)

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_SELECTED = "selected"
    }
}

class AndroidLogProvider(private val file: java.io.File) : LogProvider {
    override val path: String get() = file.absolutePath

    override suspend fun read(): String = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching "Лог пока пуст: ядро ещё не запускалось."
            file.readLines().takeLast(400).joinToString("\n")
        }.getOrElse { "Не удалось прочитать лог: ${it.message}" }
    }

    override fun clear() {
        runCatching { file.writeText("") }
    }
}

class AndroidContainer(activity: ComponentActivity) : AppContainer {
    private val geoDir = File(activity.filesDir, "geo").apply { mkdirs() }.absolutePath
    // Лог ядра — во внешнем каталоге приложения: adb читает его без run-as
    // (run-as на этом устройстве падает на SELinux).
    private val logPath = File(
        activity.getExternalFilesDir(null) ?: activity.filesDir,
        "xray.log",
    ).absolutePath

    override val store: ServerStore = AndroidServerStore(activity.applicationContext)
    override val settings: SettingsStore = AndroidSettingsStore(activity.applicationContext)
    override val vpn: VpnController = AndroidVpnController(activity, geoDir, logPath, settings)
    override val logs: LogProvider = AndroidLogProvider(java.io.File(logPath))
    override val transfer: TransferService = AndroidTransferService(activity.applicationContext)
    override val update: UpdateService = AndroidUpdateService(activity.applicationContext)
    override val geoDirectory: String = geoDir
    override val subscriptions: SubscriptionFetcher = AndroidSubscriptionFetcher(activity.applicationContext)
    override val packages: PackageLister = AndroidPackageLister(activity.applicationContext)
    override val pinger: Pinger = AndroidPinger()
}
