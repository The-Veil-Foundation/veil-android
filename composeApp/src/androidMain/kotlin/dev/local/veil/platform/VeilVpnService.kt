package dev.local.veil.platform

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.local.veil.core.CoreEngine
import dev.local.veil.core.SplitMode
import dev.local.veil.core.TunnelOptions
import dev.local.veil.core.TunAddress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class VeilVpnService : VpnService() {

    companion object {
        const val ACTION_START = "dev.local.veil.START"
        const val ACTION_STOP = "dev.local.veil.STOP"
        const val ACTION_RELOAD = "dev.local.veil.RELOAD"
        const val ACTION_PAUSE = "dev.local.veil.PAUSE"
        const val ACTION_UPDATE_NOTIFICATION = "dev.local.veil.UPDATE_NOTIFICATION"
        const val ACTION_DISMISS_NOTIFICATION = "dev.local.veil.DISMISS_NOTIFICATION"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_IPV6 = "ipv6"
        const val EXTRA_DNS = "dns"
        const val EXTRA_GEO = "geo"
        const val EXTRA_SERVER = "server"
        const val EXTRA_SPLIT_MODE = "split_mode"
        const val EXTRA_SPLIT_PACKAGES = "split_packages"
        const val EXTRA_LIVE_NOTIFICATION = "live_notification"
        const val EXTRA_ENGINE = "engine"

        private val _status = MutableStateFlow(VpnStatus())
        val status: StateFlow<VpnStatus> = _status.asStateFlow()

        fun publish(next: VpnStatus) {
            _status.value = next
        }

        fun reset() {
            _status.value = VpnStatus()
        }

        fun start(
            context: Context,
            configJson: String,
            serverName: String,
            options: TunnelOptions,
            geoDirectory: String,
            splitMode: SplitMode = SplitMode.OFF,
            splitPackages: Set<String> = emptySet(),
            liveNotification: Boolean = true,
            engine: CoreEngine? = null,
        ) {
            val intent = Intent(context, VeilVpnService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, configJson)
                putExtra(EXTRA_SERVER, serverName)
                putExtra(EXTRA_MTU, options.mtu)
                putExtra(EXTRA_IPV6, options.ipv6)
                putExtra(EXTRA_DNS, options.dnsServers.toTypedArray())
                putExtra(EXTRA_GEO, geoDirectory)
                putExtra(EXTRA_SPLIT_MODE, splitMode.name)
                putExtra(EXTRA_SPLIT_PACKAGES, splitPackages.toTypedArray())
                putExtra(EXTRA_LIVE_NOTIFICATION, liveNotification)
                putExtra(EXTRA_ENGINE, (engine ?: CoreEngine.XRAY).name)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reload(
            context: Context,
            configJson: String,
            serverName: String,
            splitMode: SplitMode? = null,
            splitPackages: Set<String>? = null,
            engine: CoreEngine? = null,
        ) {
            context.startService(
                Intent(context, VeilVpnService::class.java).apply {
                    action = ACTION_RELOAD
                    putExtra(EXTRA_CONFIG, configJson)
                    putExtra(EXTRA_SERVER, serverName)
                    if (engine != null) putExtra(EXTRA_ENGINE, engine.name)

                    if (splitMode != null && splitPackages != null) {
                        putExtra(EXTRA_SPLIT_MODE, splitMode.name)
                        putExtra(EXTRA_SPLIT_PACKAGES, splitPackages.toTypedArray())
                    }
                },
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VeilVpnService::class.java).apply { action = ACTION_STOP },
            )
        }

        fun togglePause(context: Context) {
            context.startService(
                Intent(context, VeilVpnService::class.java).apply { action = ACTION_PAUSE },
            )
        }

        fun updateNotification(context: Context, enabled: Boolean) {
            if (status.value.phase !in setOf(VpnPhase.CONNECTED, VpnPhase.PAUSED)) return
            context.startService(
                Intent(context, VeilVpnService::class.java).apply {
                    action = ACTION_UPDATE_NOTIFICATION
                    putExtra(EXTRA_LIVE_NOTIFICATION, enabled)
                },
            )
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var geoDirectory: String = ""
    private var connectedAt: Long = 0L

    private var coreFd: ParcelFileDescriptor? = null

    private var engine: CoreEngine = CoreEngine.XRAY

    private fun startCore(configJson: String, core: CoreEngine = engine): Result<Unit> {
        val master = tun ?: return Result.failure(IllegalStateException("TUN не создан"))
        runCatching { coreFd?.close() }
        val child = ParcelFileDescriptor.dup(master.fileDescriptor)
        coreFd = child
        engine = core
        if (core == CoreEngine.SING_BOX) {
            runCatching { singbox.Singbox.install(applicationContext) }
        }
        val sharing = dev.local.veil.sharing.AndroidInternetSharing.get(applicationContext)
        val sharedConfig = runCatching { sharing.attach(configJson, core) }.getOrElse { return Result.failure(it) }
        return when (core) {
            CoreEngine.SING_BOX -> SingboxBridge.start(sharedConfig, child.fd, geoDirectory, 0)
            else -> XrayBridge.start(sharedConfig, child.fd, geoDirectory, 0)
        }.onSuccess { sharing.onCoreReady() }
    }

    private fun stopCores(reconnecting: Boolean = false) {
        dev.local.veil.sharing.AndroidInternetSharing.get(applicationContext).onCoreStopping(reconnecting)
        runCatching { XrayBridge.stop() }
        runCatching { SingboxBridge.stop() }
    }

    private fun coreVersion(core: CoreEngine = engine): String? = when (core) {
        CoreEngine.SING_BOX -> SingboxBridge.version()
        else -> XrayBridge.version()
    }

    private data class TunnelParams(
        val mtu: Int,
        val ipv6: Boolean,
        val dns: List<String>,
        val splitMode: SplitMode,
        val splitPackages: Set<String>,
    )

    private var params: TunnelParams? = null

    private fun openTun(params: TunnelParams): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Veil")
            .setMtu(params.mtu)
            .addAddress(TunAddress.IPV4, TunAddress.IPV4_PREFIX)
            .addRoute("0.0.0.0", 0)

        if (params.ipv6) {
            builder.addAddress(TunAddress.IPV6, TunAddress.IPV6_PREFIX)
            builder.addRoute("::", 0)
        }

        params.dns.forEach { builder.addDnsServer(it) }

        val others = params.splitPackages.filter { it != packageName }
        when (params.splitMode) {
            SplitMode.OFF -> runCatching { builder.addDisallowedApplication(packageName) }
            SplitMode.EXCLUDE -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                others.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            }
            SplitMode.INCLUDE -> {
                val added = others.count { pkg -> runCatching { builder.addAllowedApplication(pkg) }.isSuccess }

                if (added == 0) {
                    throw IllegalStateException("Режим «Только»: не выбрано ни одного установленного приложения")
                }
            }
        }

        return builder.establish()
    }

    private var activeServerName: String = ""

    private var paused = false

    private var lastConfig: String = ""
    private var lastLiveNotification: Boolean = true
    private var notificationDismissed = false
    private var lastEngine: CoreEngine = CoreEngine.XRAY

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISMISS_NOTIFICATION || intent?.action == ACTION_UPDATE_NOTIFICATION) {
            if (lastConfig.isBlank()) {
                stopSelf(startId)
                return START_NOT_STICKY
            }
            if (intent.action == ACTION_DISMISS_NOTIFICATION) {
                notificationDismissed = true
            } else {
                lastLiveNotification = intent.getBooleanExtra(EXTRA_LIVE_NOTIFICATION, true)
                notificationDismissed = false
                notifyState()
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            teardown(resetStatus = true)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_PAUSE) {
            if (paused) resume() else pause()
            return START_STICKY
        }

        if (intent?.action == ACTION_RELOAD) {
            val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
            val serverName = intent.getStringExtra(EXTRA_SERVER).orEmpty()
            val newMode = intent.getStringExtra(EXTRA_SPLIT_MODE)
                ?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() }
            val newPackages = intent.getStringArrayExtra(EXTRA_SPLIT_PACKAGES)?.toSet()
            if (newMode != null && newPackages != null) {
                params = params?.copy(splitMode = newMode, splitPackages = newPackages)
            }
            val newEngine = intent.getStringExtra(EXTRA_ENGINE)
                ?.let { runCatching { CoreEngine.valueOf(it) }.getOrNull() }
            reload(config, serverName, newEngine)
            return START_STICKY
        }

        notificationDismissed = false
        lastLiveNotification = intent?.getBooleanExtra(EXTRA_LIVE_NOTIFICATION, true) ?: true
        startForeground(
            VpnNotifications.ID,
            VpnNotifications.build(
                this,
                VpnStatus(VpnPhase.CONNECTING, serverName = intent?.getStringExtra(EXTRA_SERVER).orEmpty()),
                liveUpdates = lastLiveNotification,
            ),
        )

        val config = intent?.getStringExtra(EXTRA_CONFIG).orEmpty()
        val serverName = intent?.getStringExtra(EXTRA_SERVER).orEmpty()
        val mtu = intent?.getIntExtra(EXTRA_MTU, 1500) ?: 1500
        val ipv6 = intent?.getBooleanExtra(EXTRA_IPV6, true) ?: true
        val dns = intent?.getStringArrayExtra(EXTRA_DNS)?.toList().orEmpty()
        geoDirectory = intent?.getStringExtra(EXTRA_GEO).orEmpty()
        val splitMode = runCatching {
            SplitMode.valueOf(intent?.getStringExtra(EXTRA_SPLIT_MODE) ?: SplitMode.OFF.name)
        }.getOrDefault(SplitMode.OFF)
        val splitPackages = intent?.getStringArrayExtra(EXTRA_SPLIT_PACKAGES)?.toSet().orEmpty()
        val liveNotification = intent?.getBooleanExtra(EXTRA_LIVE_NOTIFICATION, true) ?: true
        val core = intent?.getStringExtra(EXTRA_ENGINE)
            ?.let { runCatching { CoreEngine.valueOf(it) }.getOrNull() }
            ?: CoreEngine.XRAY

        connect(config, serverName, mtu, ipv6, dns, splitMode, splitPackages, liveNotification, core)
        return START_STICKY
    }

    private fun connect(
        configJson: String,
        serverName: String,
        mtu: Int,
        ipv6: Boolean,
        dns: List<String>,
        splitMode: SplitMode,
        splitPackages: Set<String>,
        liveNotification: Boolean,
        core: CoreEngine,
    ) {
        publish(VpnStatus(VpnPhase.CONNECTING, serverName = serverName))
        notifyState()

        if (configJson.isBlank()) {
            fail("Пустой конфиг")
            return
        }
        val available = when (core) {
            CoreEngine.SING_BOX -> SingboxBridge.available
            else -> XrayBridge.available
        }
        if (!available) {
            fail("${core.label}: ядро не подключено к сборке")
            return
        }

        try {
            val tunnelParams = TunnelParams(mtu, ipv6, dns, splitMode, splitPackages)
            params = tunnelParams

            val fd = openTun(tunnelParams)
            if (fd == null) {
                fail("Система не выдала TUN — нет разрешения VPN")
                return
            }
            tun = fd
            engine = core

            startCore(configJson, core)
                .getOrElse {
                    fail("${core.label} не запустился: ${it.message}")
                    return
                }

            connectedAt = System.currentTimeMillis()
            activeServerName = serverName
            lastConfig = configJson
            lastEngine = core
            lastLiveNotification = liveNotification
            paused = false
            publish(
                VpnStatus(
                    phase = VpnPhase.CONNECTED,
                    serverName = serverName,
                    coreVersion = coreVersion(core),
                    connectedSince = connectedAt,
                ),
            )
            notifyState()
        } catch (e: Throwable) {
            fail(e.message ?: "Ошибка запуска ядра")
        }
    }

    private fun reload(configJson: String, serverName: String, newEngine: CoreEngine? = null) {

        if (paused) {
            if (configJson.isNotBlank()) {
                lastConfig = configJson
                activeServerName = serverName
                newEngine?.let { lastEngine = it }
            }
            publish(VpnStatus(VpnPhase.PAUSED, serverName = activeServerName, connectedSince = connectedAt))
            notifyState()
            return
        }
        val fd = tun
        if (fd == null || configJson.isBlank()) {
            stop(this)
            return
        }

        publish(VpnStatus(VpnPhase.CONNECTING, serverName = serverName))
        notifyState()
        try {
            stopCores(reconnecting = true)
            runCatching { coreFd?.close() }
            coreFd = null
            runCatching { tun?.close() }
            tun = null

            val tunnelParams = params
            val fresh = tunnelParams?.let { openTun(it) }
            if (fresh == null) {
                fail("Не удалось пересоздать TUN при смене узла")
                return
            }
            tun = fresh
            val core = newEngine ?: engine

            startCore(configJson, core)
                .getOrElse {
                    fail("${core.label} не запустился: ${it.message}")
                    return
                }

            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            activeServerName = serverName
            lastConfig = configJson
            lastEngine = core
            paused = false
            publish(
                VpnStatus(
                    phase = VpnPhase.CONNECTED,
                    serverName = serverName,
                    coreVersion = coreVersion(core),
                    connectedSince = connectedAt,
                ),
            )
            notifyState()
        } catch (e: Throwable) {
            fail(e.message ?: "Перезапуск ядра не удался")
        }
    }

    private fun pause() {
        if (paused) return
        paused = true
        stopCores()
        runCatching { coreFd?.close() }
        coreFd = null
        runCatching { tun?.close() }
        tun = null
        publish(VpnStatus(VpnPhase.PAUSED, serverName = activeServerName, connectedSince = connectedAt))
        notifyState()
    }

    private fun resume() {
        if (!paused) return
        val tunnelParams = params
        if (tunnelParams == null || lastConfig.isBlank()) {
            teardown(resetStatus = true)
            stopSelf()
            return
        }
        publish(VpnStatus(VpnPhase.CONNECTING, serverName = activeServerName))
        notifyState()
        val fresh = runCatching { openTun(tunnelParams) }.getOrNull()
        if (fresh == null) {
            paused = false
            fail("Не удалось вернуть VPN: проверьте разрешение на VPN")
            return
        }
        tun = fresh
        startCore(lastConfig, lastEngine).onFailure {
            paused = false
            fail("Возобновление не удалось: ${it.message}")
            return
        }
        paused = false
        publish(
            VpnStatus(
                phase = VpnPhase.CONNECTED,
                serverName = activeServerName,
                coreVersion = coreVersion(lastEngine),
                connectedSince = connectedAt,
            ),
        )
        notifyState()
    }

    private fun teardown(resetStatus: Boolean) {
        stopCores()
        runCatching { coreFd?.close() }
        coreFd = null
        runCatching { tun?.close() }
        tun = null
        if (resetStatus) {
            reset()
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
                else @Suppress("DEPRECATION") stopForeground(true)
            }
        }
    }

    private fun fail(message: String) {
        teardown(resetStatus = false)
        publish(VpnStatus(VpnPhase.ERROR, message))
        notifyState()
        stopSelf()
    }

    override fun onRevoke() {
        teardown(resetStatus = true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown(resetStatus = false)
        super.onDestroy()
    }

    private fun notifyState() {
        if (notificationDismissed) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(VpnNotifications.ID, VpnNotifications.build(this, status.value, lastLiveNotification))
    }
}
