package dev.local.veil.platform

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.content.Context
import android.content.res.Configuration
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.local.veil.core.CoreEngine
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.SingboxConfig
import dev.local.veil.core.Subscription
import dev.local.veil.core.TunnelOptions
import dev.local.veil.core.XrayInbound
import dev.local.veil.core.XrayConfig
import dev.local.veil.core.xrayCompatible
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
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

    private val isTelevision =
        (activity.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    /** Слабое устройство: реже опрашиваем счётчики, чтобы не греть CPU. */
    private val isLowRamAndroid =
        (activity.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.isLowRamDevice == true

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _status = MutableStateFlow(VpnStatus())
    override val status: StateFlow<VpnStatus> = _status.asStateFlow()

    override val coreAvailable: Boolean get() = XrayBridge.available || SingboxBridge.available
    override val coreVersion: String?
        get() {
            val singbox = SingboxBridge.version()?.let { "sing-box $it" }
            val xray = XrayBridge.version()?.let { "Xray $it" }
            // Показываем ядро, которым реально поднят туннель.
            return if (activeEngine == CoreEngine.SING_BOX) singbox ?: xray else xray ?: singbox
        }

    private var pollJob: Job? = null
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    /** Ядро текущего туннеля — от него зависят счётчики трафика. */
    private var activeEngine: CoreEngine = CoreEngine.XRAY

    private val permissionLauncher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingPermission?.complete(result.resultCode == android.app.Activity.RESULT_OK)
            pendingPermission = null
        }

    init {
        // libbox требует basePath до первого newService; ставим его сразу,
        // чтобы работали и туннель, и пробное ядро.
        runCatching { singbox.Singbox.install(activity.applicationContext) }
        scope.launch {
            VeilVpnService.status.collect { service ->
                _status.value = service.withCounters()
                if (service.isConnected) startPolling() else stopPolling()
            }
        }
    }

    /** Секрет Clash API sing-box: новый на каждый запуск, как на macOS. */
    private val clashSecret: String =
        java.math.BigInteger(128, java.security.SecureRandom()).toString(16)

    override suspend fun connect(server: ProxyConfig, options: TunnelOptions): Result<Unit> =
        runCatching {
            val effective = options.copy(
                logFile = if (logFile.isNotEmpty()) logFile else options.logFile,
                geoAvailable = hasGeoAssets(),
                clashSecret = clashSecret,
            )
            val engine = engineFor(server, effective)
            val config = configFor(server, effective, engine)
            if (!coreAvailable) error("ядро не подключено к сборке")
            if (engine == CoreEngine.SING_BOX && !SingboxBridge.available) {
                error("Для ${server.proto.wire} нужно ядро sing-box — оно ещё не подключено к сборке")
            }
            if (engine == CoreEngine.XRAY && !server.xrayCompatible) {
                error("Протокол ${server.proto.wire} не поддерживается Xray")
            }
            if (!ensurePermission()) error("Разрешение на VPN не выдано")

            // Каждая новая сессия начинается с чистого лога.
            if (logFile.isNotEmpty()) prepareLog(engine, logFile)
            activeEngine = engine

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
                engine = engine,
            )
        }

    /** Есть ли в каталоге ядра geoip.dat и geosite.dat. */
    private fun hasGeoAssets(): Boolean {
        fun present(name: String) = java.io.File(geoDirectory, name).let { it.exists() && it.length() > 0 }
        return present("geoip.dat") && present("geosite.dat")
    }

    /** Каким ядром поднимать узел: AUTO выбирает по протоколу и транспорту. */
    private fun engineFor(server: ProxyConfig, options: TunnelOptions): CoreEngine =
        options.coreEngine.resolve(server)

    private suspend fun configFor(
        server: ProxyConfig,
        options: TunnelOptions,
        engine: CoreEngine,
    ): String = when (engine) {
        // Резолвер понимает только форму Xray; sing-box пока получает конфиг как есть.
        CoreEngine.SING_BOX -> SingboxConfig.build(server, options).toString()
        else -> resolveEndpoints(XrayConfig.json(server, options))
    }

    private fun prepareLog(engine: CoreEngine, path: String) {
        if (engine == CoreEngine.SING_BOX) SingboxBridge.prepareLog(path) else XrayBridge.prepareLog(path)
    }

    private fun upTotal(): Long =
        if (activeEngine == CoreEngine.SING_BOX) SingboxBridge.uplinkTotal() else XrayBridge.uplinkTotal()

    private fun downTotal(): Long =
        if (activeEngine == CoreEngine.SING_BOX) SingboxBridge.downlinkTotal() else XrayBridge.downlinkTotal()

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
            val effective = options.forProbe().copy(
                logFile = probeLog,
                logLevel = "debug",
                geoAvailable = hasGeoAssets(),
            )
            val engine = engineFor(server, effective)
            if (engine == CoreEngine.SING_BOX && !SingboxBridge.available) return@withContext null
            val port = 21000 + (System.nanoTime() % 500).toInt()
            // Одноразовый логин/пароль: пока идёт проверка, чужое приложение
            // на телефоне не сможет пустить трафик через этот SOCKS.
            val rnd = java.security.SecureRandom()
            val user = "p" + java.math.BigInteger(64, rnd).toString(16)
            val pass = java.math.BigInteger(128, rnd).toString(16)
            val config = when (engine) {
                CoreEngine.SING_BOX -> SingboxConfig.build(
                    server,
                    effective,
                    SingboxConfig.Inbound.LocalProxy(socks = port, user = user, pass = pass),
                ).toString()
                else -> resolveEndpoints(
                    XrayConfig.json(
                        server,
                        effective,
                        XrayInbound.LocalProxy(socks = port, http = port + 1, user = user, pass = pass),
                    ),
                    dump = false,
                )
            }
            try {
                val started = if (engine == CoreEngine.SING_BOX) {
                    SingboxBridge.startProbe(config, geoDirectory)
                } else {
                    XrayBridge.startProbe(config, geoDirectory)
                }
                started
                    .onFailure { android.util.Log.w("VeilXray", "пробное ядро не поднялось: ${it.message}") }
                    .getOrElse { return@withContext null }
                java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                    override fun getPasswordAuthentication() =
                        java.net.PasswordAuthentication(user, pass.toCharArray())
                })
                block(port)
            } finally {
                java.net.Authenticator.setDefault(null)
                if (engine == CoreEngine.SING_BOX) {
                    runCatching { SingboxBridge.stopProbe() }
                } else {
                    runCatching { XrayBridge.stopProbe() }
                }
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
     * Реальный пинг пачкой, как ProxyProbe на macOS: одно пробное ядро на
     * [dev.local.veil.core.PingBatch.CHUNK] узлов, по SOCKS-входу на узел,
     * запросы параллельно. Узлы с готовым конфигом из подписки мерятся по
     * одному — у них своя топология выходов.
     */
    override suspend fun realPingBatch(
        servers: List<ProxyConfig>,
        options: TunnelOptions,
        timeoutMs: Int,
        report: suspend (id: String, ms: Int?) -> Unit,
    ) {
        if (!coreAvailable) {
            servers.forEach { report(it.id, null) }
            return
        }
        val effective = options.forProbe().copy(
            logFile = null,
            logLevel = "warning",
            geoAvailable = hasGeoAssets(),
        )
        val (batchable, single) = servers.partition { dev.local.veil.core.PingBatch.batchable(it) }
        val byEngine = batchable.groupBy { engineFor(it, effective) }
        for ((engine, nodes) in byEngine) {
            if (engine == CoreEngine.SING_BOX && !SingboxBridge.available) {
                nodes.forEach { report(it.id, null) }
                continue
            }
            for (chunk in nodes.chunked(dev.local.veil.core.PingBatch.CHUNK)) {
                pingChunk(chunk, engine, effective, timeoutMs, report)
            }
        }
        single.forEach { report(it.id, realPing(it, options, timeoutMs)) }
    }

    private suspend fun pingChunk(
        nodes: List<ProxyConfig>,
        engine: CoreEngine,
        options: TunnelOptions,
        timeoutMs: Int,
        report: suspend (id: String, ms: Int?) -> Unit,
    ) {
        val results = probeMutex.withLock {
            withContext(Dispatchers.IO) {
                val ports = freeLoopbackPorts(nodes.size)
                if (ports.size != nodes.size) return@withContext null
                val rnd = java.security.SecureRandom()
                val user = "p" + java.math.BigInteger(64, rnd).toString(16)
                val pass = java.math.BigInteger(128, rnd).toString(16)
                val config = runCatching {
                    when (engine) {
                        CoreEngine.SING_BOX -> dev.local.veil.core.PingBatch.singbox(nodes, ports, user, pass, options).toString()
                        else -> resolveEndpoints(
                            dev.local.veil.core.PingBatch.xray(nodes, ports, user, pass, options).toString(),
                            dump = false,
                        )
                    }
                }.onFailure { android.util.Log.w("VeilXray", "конфиг пинга не собрался: ${it.message}") }
                    .getOrNull() ?: return@withContext null
                try {
                    val started = if (engine == CoreEngine.SING_BOX) {
                        SingboxBridge.startProbe(config, geoDirectory)
                    } else {
                        XrayBridge.startProbe(config, geoDirectory)
                    }
                    started.onFailure { android.util.Log.w("VeilXray", "ядро пинга не поднялось: ${it.message}") }
                        .getOrElse { return@withContext null }
                    java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                        override fun getPasswordAuthentication() =
                            java.net.PasswordAuthentication(user, pass.toCharArray())
                    })
                    waitForListener(ports.last(), 3000)
                    val semaphore = kotlinx.coroutines.sync.Semaphore(12)
                    kotlinx.coroutines.coroutineScope {
                        nodes.mapIndexed { index, node ->
                            async {
                                semaphore.withPermit {
                                    // Первый запрос платит за рукопожатие, второй — чистый RTT.
                                    val cold = requestThroughSocks(ports[index], timeoutMs)
                                    val ms = if (cold == null) null else requestThroughSocks(ports[index], timeoutMs) ?: cold
                                    report(node.id, ms)
                                    node.id
                                }
                            }
                        }.awaitAll()
                    }
                } finally {
                    java.net.Authenticator.setDefault(null)
                    if (engine == CoreEngine.SING_BOX) {
                        runCatching { SingboxBridge.stopProbe() }
                    } else {
                        runCatching { XrayBridge.stopProbe() }
                    }
                }
            }
        }
        if (results == null) nodes.forEach { report(it.id, null) }
    }

    /** Свободные порты loopback: сокеты держим, пока не выбраны все, чтобы порты не повторились. */
    private fun freeLoopbackPorts(count: Int): List<Int> {
        val sockets = ArrayList<java.net.ServerSocket>()
        return try {
            repeat(count) {
                sockets += java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            }
            sockets.map { it.localPort }
        } catch (_: Exception) {
            emptyList()
        } finally {
            sockets.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun waitForListener(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = runCatching {
                java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200) }
            }.isSuccess
            if (ok) return
            delay(40)
        }
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
            if (!coreAvailable) error("ядро не подключено к сборке")

            val effective = options.copy(
                logFile = if (logFile.isNotEmpty()) logFile else options.logFile,
                geoAvailable = hasGeoAssets(),
                clashSecret = clashSecret,
            )
            val engine = engineFor(server, effective)
            if (engine == CoreEngine.SING_BOX && !SingboxBridge.available) {
                error("Для ${server.proto.wire} нужно ядро sing-box — оно ещё не подключено к сборке")
            }
            if (engine == CoreEngine.XRAY && !server.xrayCompatible) {
                error("Протокол ${server.proto.wire} не поддерживается Xray")
            }
            val config = configFor(server, effective, engine)
            activeEngine = engine

            if (_status.value.isConnected) {
                val settings = settingsStore.load()
                VeilVpnService.reload(
                    activity.applicationContext,
                    config,
                    server.name,
                    splitMode = settings.splitMode,
                    splitPackages = settings.splitPackages,
                    engine = engine,
                )
            } else {
                if (!ensurePermission()) error("Разрешение на VPN не выдано")
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
                    engine = engine,
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
            coreVersion = coreVersion ?: this@AndroidVpnController.coreVersion,
            uplink = upTotal(),
            downlink = downTotal(),
        ) else copy(uplink = 0, downlink = 0)

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var lastUp = 0L
            var lastDown = 0L
            var lastAt = 0L
            while (isActive) {
                // На ТВ и слабых телефонах скорость счётчиков не нужна каждую
                // секунду: более редкий тик заметно снижает лишние перерисовки.
                delay(
                    when {
                        isTelevision -> 2000
                        isLowRamAndroid -> 2000
                        else -> 1000
                    },
                )
                val up = upTotal()
                val down = downTotal()
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
    override val geo: GeoService = AndroidGeoService(File(geoDir))
    override val subscriptions: SubscriptionFetcher = AndroidSubscriptionFetcher(activity.applicationContext)
    override val packages: PackageLister = AndroidPackageLister(activity.applicationContext)
    override val pinger: Pinger = AndroidPinger()
}
