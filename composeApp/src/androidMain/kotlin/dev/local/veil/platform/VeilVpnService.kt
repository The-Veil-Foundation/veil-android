package dev.local.veil.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import dev.local.veil.MainActivity
import dev.local.veil.R
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
        const val EXTRA_CONFIG = "config"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_IPV6 = "ipv6"
        const val EXTRA_DNS = "dns"
        const val EXTRA_GEO = "geo"
        const val EXTRA_SERVER = "server"
        const val EXTRA_SPLIT_MODE = "split_mode"
        const val EXTRA_SPLIT_PACKAGES = "split_packages"
        const val EXTRA_LIVE_NOTIFICATION = "live_notification"

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
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun reload(context: Context, configJson: String, serverName: String) {
            context.startService(
                Intent(context, VeilVpnService::class.java).apply {
                    action = ACTION_RELOAD
                    putExtra(EXTRA_CONFIG, configJson)
                    putExtra(EXTRA_SERVER, serverName)
                },
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, VeilVpnService::class.java).apply { action = ACTION_STOP },
            )
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var geoDirectory: String = ""
    private var connectedAt: Long = 0L

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

        if (intent?.action == ACTION_RELOAD) {
            val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
            val serverName = intent.getStringExtra(EXTRA_SERVER).orEmpty()
            reload(config, serverName)
            return START_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification("Подключение…", null, ongoing = true))

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

        connect(config, serverName, mtu, ipv6, dns, splitMode, splitPackages, liveNotification)
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
    ) {
        publish(VpnStatus(VpnPhase.CONNECTING, serverName = serverName))

        if (configJson.isBlank()) {
            fail("Пустой конфиг")
            return
        }
        if (!XrayBridge.available) {
            fail("xray.aar не подключён к сборке")
            return
        }

        try {
            val builder = Builder()
                .setSession("Veil")
                .setMtu(mtu)
                .addAddress("198.18.0.1", 24)
                .addRoute("0.0.0.0", 0)

            if (ipv6) {
                builder.addAddress("fd6e:a81b:704f:1211::1", 64)
                builder.addRoute("::", 0)
            }

            dns.forEach { builder.addDnsServer(it) }

            // Ядро живёт в нашем процессе: исключаем приложение из туннеля,
            // иначе его сокеты до сервера уйдут в тот же TUN — петля.
            runCatching { builder.addDisallowedApplication(packageName) }

            when (splitMode) {
                SplitMode.OFF -> Unit
                SplitMode.EXCLUDE -> splitPackages.forEach { pkg ->
                    if (pkg != packageName) runCatching { builder.addDisallowedApplication(pkg) }
                }
                SplitMode.INCLUDE -> splitPackages.forEach { pkg ->
                    if (pkg != packageName) runCatching { builder.addAllowedApplication(pkg) }
                }
            }

            val fd = builder.establish()
            if (fd == null) {
                fail("Система не выдала TUN — нет разрешения VPN")
                return
            }
            tun = fd

            XrayBridge.start(configJson, fd.fd, geoDirectory, 0)
                .getOrElse {
                    fail("Xray не запустился: ${it.message}")
                    return
                }

            connectedAt = System.currentTimeMillis()
            publish(
                VpnStatus(
                    phase = VpnPhase.CONNECTED,
                    serverName = serverName,
                    coreVersion = XrayBridge.version(),
                    connectedSince = connectedAt,
                ),
            )
            startLiveNotification(serverName, liveNotification)
        } catch (e: Throwable) {
            fail(e.message ?: "Ошибка запуска ядра")
        }
    }

    private fun reload(configJson: String, serverName: String) {
        val fd = tun
        if (fd == null || configJson.isBlank()) {
            stop(this)
            return
        }

        publish(VpnStatus(VpnPhase.CONNECTING, serverName = serverName))
        try {
            XrayBridge.stop()
            XrayBridge.start(configJson, fd.fd, geoDirectory, 0)
                .getOrElse {
                    fail("Перезапуск ядра не удался: ${it.message}")
                    return
                }
            // Время сессии не обнуляем: смена узла не должна выглядеть
            // как новое подключение.
            if (connectedAt == 0L) connectedAt = System.currentTimeMillis()
            lastTick = 0L
            publish(
                VpnStatus(
                    phase = VpnPhase.CONNECTED,
                    serverName = serverName,
                    coreVersion = XrayBridge.version(),
                    connectedSince = connectedAt,
                ),
            )
            notify(serverName, live = true)
        } catch (e: Throwable) {
            fail(e.message ?: "Перезапуск ядра не удался")
        }
    }

    /**
     * Живое уведомление: раз в секунду обновляет трафик и время, вместо
     * статичной плашки «Защищено».
     */
    private fun startLiveNotification(serverName: String, enabled: Boolean) {
        notificationJob?.cancel()
        if (!enabled) {
            notify(serverName, live = false)
            return
        }
        notificationJob = serviceScope.launch {
            while (isActive) {
                notify(serverName, live = true)
                delay(1000)
            }
        }
    }

    private fun teardown(resetStatus: Boolean) {
        notificationJob?.cancel()
        notificationJob = null
        runCatching { XrayBridge.stop() }
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
        val subtitle = if (live) {
            val now = System.currentTimeMillis()
            val seconds = ((now - connectedAt) / 1000).coerceAtLeast(0)
            val down = XrayBridge.downlinkTotal()
            val up = XrayBridge.uplinkTotal()
            val elapsed = if (lastTick == 0L) 1.0 else ((now - lastTick) / 1000.0).coerceAtLeast(0.2)
            val downSpeed = if (lastTick == 0L) 0L else ((down - lastDown) / elapsed).toLong()
            val upSpeed = if (lastTick == 0L) 0L else ((up - lastUp) / elapsed).toLong()
            lastTick = now
            lastDown = down
            lastUp = up
            "↓ ${formatSpeed(downSpeed)} · ↑ ${formatSpeed(upSpeed)} · ${formatDuration(seconds)}"
        } else {
            null
        }
        manager.notify(NOTIFICATION_ID, buildNotification(text, subtitle, ongoing = true))
    }

    private fun buildNotification(
        text: String,
        subtitle: String?,
        ongoing: Boolean,
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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle("Veil · $text")
            .setContentText(subtitle ?: "Туннель активен")
            .setSubText(subtitle)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .addAction(0, "Отключить", stopAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

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
