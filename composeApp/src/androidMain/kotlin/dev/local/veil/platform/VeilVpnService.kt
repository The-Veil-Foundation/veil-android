package dev.local.veil.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.local.veil.MainActivity
import dev.local.veil.R
import dev.local.veil.core.CoreEngine
import dev.local.veil.core.SplitMode
import dev.local.veil.core.TunnelOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Android-аналог `PacketTunnelProvider`.
 *
 * Тот же путь пакета, что на iOS: system routing → TUN → Xray `tun` inbound
 * (gVisor) → outbound → сервер. Интерфейс создаёт `VpnService.establish()`,
 * дескриптор уходит в Xray через `xray.tun.fd`.
 */
class VeilVpnService : VpnService() {

    companion object {
        private const val CHANNEL_ID = "veil_vpn"
        private const val NOTIFICATION_ID = 41

        const val ACTION_START = "dev.local.veil.START"
        const val ACTION_STOP = "dev.local.veil.STOP"
        const val ACTION_RELOAD = "dev.local.veil.RELOAD"
        const val ACTION_PAUSE = "dev.local.veil.PAUSE"
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
                    // Новые настройки раздельного туннеля применяются при пересоздании TUN.
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

        /** Переключает паузу: из уведомления и из приложения. */
        fun togglePause(context: Context) {
            context.startService(
                Intent(context, VeilVpnService::class.java).apply { action = ACTION_PAUSE },
            )
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var geoDirectory: String = ""
    private var connectedAt: Long = 0L

    /**
     * Копия TUN-дескриптора для ядра. gVisor закрывает fd при остановке,
     * поэтому мастер-дескриптор всегда остаётся у сервиса, а ядро получает dup.
     */
    private var coreFd: ParcelFileDescriptor? = null

    /** Ядро, которым поднят текущий туннель. */
    private var engine: CoreEngine = CoreEngine.XRAY

    /** Запускает ядро на дубликате TUN: перезапуск не убивает интерфейс. */
    private fun startCore(configJson: String, core: CoreEngine = engine): Result<Unit> {
        val master = tun ?: return Result.failure(IllegalStateException("TUN не создан"))
        runCatching { coreFd?.close() }
        val child = ParcelFileDescriptor.dup(master.fileDescriptor)
        coreFd = child
        engine = core
        return when (core) {
            CoreEngine.SING_BOX -> SingboxBridge.start(configJson, child.fd, geoDirectory, 0)
            else -> XrayBridge.start(configJson, child.fd, geoDirectory, 0)
        }
    }

    private fun stopCores() {
        runCatching { XrayBridge.stop() }
        runCatching { SingboxBridge.stop() }
    }

    private fun coreVersion(core: CoreEngine = engine): String? = when (core) {
        CoreEngine.SING_BOX -> SingboxBridge.version()
        else -> XrayBridge.version()
    }

    private fun uplinkTotal(): Long =
        if (engine == CoreEngine.SING_BOX) SingboxBridge.uplinkTotal() else XrayBridge.uplinkTotal()

    private fun downlinkTotal(): Long =
        if (engine == CoreEngine.SING_BOX) SingboxBridge.downlinkTotal() else XrayBridge.downlinkTotal()

    /** Параметры TUN: нужны, чтобы пересоздать интерфейс при смене узла. */
    private data class TunnelParams(
        val mtu: Int,
        val ipv6: Boolean,
        val dns: List<String>,
        val splitMode: SplitMode,
        val splitPackages: Set<String>,
    )

    private var params: TunnelParams? = null

    /**
     * Создаёт TUN. При смене узла интерфейс пересоздаётся: ядро (gVisor)
     * закрывает дескриптор при остановке, и старый fd больше не годится.
     */
    private fun openTun(params: TunnelParams): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Veil")
            .setMtu(params.mtu)
            .addAddress("198.18.0.1", 24)
            .addRoute("0.0.0.0", 0)

        if (params.ipv6) {
            builder.addAddress("fd6e:a81b:704f:1211::1", 64)
            builder.addRoute("::", 0)
        }

        params.dns.forEach { builder.addDnsServer(it) }

        // Android не позволяет смешивать «разрешённые» и «запрещённые» приложения:
        // addAllowedApplication после addDisallowedApplication кидает исключение,
        // а раньше оно глоталось через runCatching — режим «Только» не работал.
        // Само приложение в режиме INCLUDE и так мимо туннеля (его нет в списке).
        val others = params.splitPackages.filter { it != packageName }
        when (params.splitMode) {
            SplitMode.OFF -> runCatching { builder.addDisallowedApplication(packageName) }
            SplitMode.EXCLUDE -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                others.forEach { pkg -> runCatching { builder.addDisallowedApplication(pkg) } }
            }
            SplitMode.INCLUDE -> {
                val added = others.count { pkg -> runCatching { builder.addAllowedApplication(pkg) }.isSuccess }
                // Пустой список allowed означает «все приложения» — это петля для ядра
                // и обратное тому, что просил пользователь.
                if (added == 0) {
                    throw IllegalStateException("Режим «Только»: не выбрано ни одного установленного приложения")
                }
            }
        }

        return builder.establish()
    }

    /** Имя узла, к которому подключены сейчас: его показывает уведомление. */
    private var activeServerName: String = ""

    /** Состояние паузы: ядро остановлено, TUN остаётся поднят — трафик не течёт. */
    private var paused = false

    /** Последний конфиг/имя/режим уведомления — чтобы возобновить после паузы. */
    private var lastConfig: String = ""
    private var lastServerName: String = ""
    private var lastLiveNotification: Boolean = true
    private var lastEngine: CoreEngine = CoreEngine.XRAY

    /** Предыдущие счётчики — чтобы считать скорость в уведомлении. */
    private var lastDown = 0L
    private var lastUp = 0L
    private var lastTick = 0L

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        startForeground(NOTIFICATION_ID, buildNotification("Подключение…", null, 0))

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
            lastServerName = serverName
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
            startLiveNotification(serverName, liveNotification)
        } catch (e: Throwable) {
            fail(e.message ?: "Ошибка запуска ядра")
        }
    }

    private fun reload(configJson: String, serverName: String, newEngine: CoreEngine? = null) {
        val fd = tun
        if (fd == null || configJson.isBlank()) {
            stop(this)
            return
        }

        publish(VpnStatus(VpnPhase.CONNECTING, serverName = serverName))
        try {
            stopCores()
            runCatching { coreFd?.close() }
            coreFd = null
            runCatching { tun?.close() }
            tun = null

            // Свежий TUN: ядро закрыло дескриптор при остановке, старый уже мёртв.
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
            // Время сессии не обнуляем: смена узла не должна выглядеть
            // как новое подключение.
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            activeServerName = serverName
            lastConfig = configJson
            lastServerName = serverName
            lastEngine = core
            paused = false
            lastTick = 0L
            publish(
                VpnStatus(
                    phase = VpnPhase.CONNECTED,
                    serverName = serverName,
                    coreVersion = coreVersion(core),
                    connectedSince = connectedAt,
                ),
            )
            notify(serverName, live = true)
        } catch (e: Throwable) {
            fail(e.message ?: "Перезапуск ядра не удался")
        }
    }

    /**
     * Уведомление больше не «живое»: обновляется только при смене состояния
     * (подключение, пауза, ошибка), а не каждую секунду.
     */
    private fun startLiveNotification(serverName: String, enabled: Boolean) {
        notificationJob?.cancel()
        notificationJob = null
        notify(activeServerName.ifEmpty { serverName }, live = false)
    }

    /**
     * Пауза: останавливаем ядро, но TUN оставляем поднятым — пакеты не текут
     * (без утечки), соединение готово мгновенно возобновиться.
     */
    private fun pause() {
        if (paused || tun == null) return
        paused = true
        stopCores()
        runCatching { coreFd?.close() }
        coreFd = null
        lastTick = 0L
        publish(VpnStatus(VpnPhase.PAUSED, serverName = activeServerName, connectedSince = connectedAt))
        notify(activeServerName, live = false)
    }

    /** Возобновление: перезапуск ядра на том же TUN-дескрипторе. */
    private fun resume() {
        if (!paused) return
        if (tun == null) {
            teardown(resetStatus = true)
            stopSelf()
            return
        }
        startCore(lastConfig, lastEngine)
            .onFailure {
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
        startLiveNotification(activeServerName, lastLiveNotification)
    }

    private fun teardown(resetStatus: Boolean) {
        notificationJob?.cancel()
        notificationJob = null
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
        notify(message, live = false)
        stopSelf()
    }

    override fun onRevoke() {
        teardown(resetStatus = true)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        teardown(resetStatus = false)
        serviceScope.cancel()
        super.onDestroy()
    }

    /* ---------------- уведомление ---------------- */

    private fun notify(text: String, live: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val now = System.currentTimeMillis()
        val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
        val subtitle = if (live && !paused) {
            val down = downlinkTotal()
            val up = uplinkTotal()
            val elapsed = if (lastTick == 0L) 1.0 else ((now - lastTick) / 1000.0).coerceAtLeast(0.2)
            val downSpeed = if (lastTick == 0L) 0L else ((down - lastDown) / elapsed).toLong()
            val upSpeed = if (lastTick == 0L) 0L else ((up - lastUp) / elapsed).toLong()
            lastTick = now
            lastDown = down
            lastUp = up
            "↓ ${formatSpeed(downSpeed)} · ↑ ${formatSpeed(upSpeed)} · ${formatDuration(seconds)}"
        } else if (paused) {
            "Пауза · ${formatDuration(seconds)}"
        } else {
            null
        }
        manager.notify(NOTIFICATION_ID, buildNotification(text, subtitle, seconds))
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(
        text: String,
        subtitle: String?,
        seconds: Long,
    ): android.app.Notification {
        ensureChannel()

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = PendingIntent.getService(
            this, 1,
            Intent(this, VeilVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseAction = PendingIntent.getService(
            this, 2,
            Intent(this, VeilVpnService::class.java).apply { action = ACTION_PAUSE },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseLabel = if (paused) "Возобновить" else "Пауза"

        // Android 16 (API 36): живой чип со шкалой стабильности через ProgressStyle.
        if (Build.VERSION.SDK_INT >= 36) {
            val green = Color.parseColor("#34A853")
            val gray = Color.parseColor("#8E918F")
            val style = Notification.ProgressStyle()
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(green)))
                .setProgressPoints(
                    listOf(
                        Notification.ProgressStyle.Point(25).setColor(gray),
                        Notification.ProgressStyle.Point(50).setColor(gray),
                        Notification.ProgressStyle.Point(75).setColor(gray),
                    ),
                )
                .setProgress(if (paused) 0 else stability(seconds))
                .setStyledByProgress(false)

            val builder = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_vpn)
                .setContentTitle("Veil · $text")
                .setContentText(subtitle ?: "Туннель активен")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setStyle(style)
            builder.addAction(0, pauseLabel, pauseAction)
            builder.addAction(0, "Отключить", stopAction)
            return builder.build()
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("Veil · $text")
            .setContentText(subtitle ?: "Туннель активен")
            .setSubText(subtitle)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, pauseLabel, pauseAction)
            .addAction(0, "Отключить", stopAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** «Стабильность»: первые 60 секунд шкала заполняется, дальше — полная. */
    private fun stability(seconds: Long): Int = (seconds * 100 / 60).toInt().coerceIn(0, 100)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Состояние защищённого соединения"
                setShowBadge(false)
            },
        )
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        bytes < 1024L * 1024 * 1024 -> "${bytes / (1024 * 1024)} МБ"
        else -> "${bytes / (1024L * 1024 * 1024)} ГБ"
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond Б/с"
        bytesPerSecond < 100 * 1024 -> "${bytesPerSecond / 1024} КБ/с"
        else -> {
            val tenths = (bytesPerSecond * 8.0 / 1_000_000.0 * 10).toInt()
            "${tenths / 10},${tenths % 10} Мбит/с"
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return listOf(h, m, s).joinToString(":") { it.toString().padStart(2, '0') }
    }
}
