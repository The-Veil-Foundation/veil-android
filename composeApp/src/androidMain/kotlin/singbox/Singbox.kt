package singbox

import android.content.Context
import android.net.TrafficStats
import android.os.Process
import libbox.BoxService
import libbox.InterfaceUpdateListener
import libbox.Libbox
import libbox.LocalDNSTransport
import libbox.NetworkInterface
import libbox.NetworkInterfaceIterator
import libbox.Notification
import libbox.PlatformInterface
import libbox.SetupOptions
import libbox.StringIterator
import libbox.TunOptions
import libbox.WIFIState
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Мост к официальному `libbox.aar` (sing-box 1.12.9).
 *
 * Класс намеренно лежит в пакете `singbox` с именем `Singbox`: существующий
 * [dev.local.veil.platform.SingboxBridge] ищет ровно `singbox.Singbox` через
 * рефлексию, поэтому приложение получает sing-box без переписывания сервиса.
 *
 * Ядро работает с уже созданным `VpnService.establish()` TUN: сервис передаёт
 * сюда fd, а [Platform.openTun] отдаёт Go-стороне его дубликат (оригинал
 * остаётся у сервиса — так же, как в Xray-пути).
 */
object Singbox {

    @Volatile private var context: Context? = null
    @Volatile private var installed = false
    @Volatile private var service: BoxService? = null
    @Volatile private var probe: BoxService? = null
    @Volatile private var running = false
    @Volatile private var logPath: String? = null
    @Volatile private var tunFd: Int = 0

    /** Вызывается из активности/сервиса: без basePath libbox не стартует. */
    @JvmStatic
    @Synchronized
    fun install(ctx: Context) {
        context = ctx.applicationContext
        if (installed) return
        val base = File(ctx.filesDir, "singbox").apply { mkdirs() }
        Libbox.setup(
            SetupOptions().apply {
                basePath = base.absolutePath
                workingPath = base.absolutePath
                tempPath = ctx.cacheDir.absolutePath
                fixAndroidStack = true
            },
        )
        installed = true
    }

    @JvmStatic
    fun version(): String? = runCatching { Libbox.version() }.getOrNull()

    @JvmStatic
    fun isRunning(): Boolean = running

    @JvmStatic
    fun prepareLog(path: String) {
        logPath = path
        runCatching {
            File(path).parentFile?.mkdirs()
            File(path).writeText("")
        }
    }

    @JvmStatic
    @Synchronized
    fun start(configJson: String, tunFd: Int, assetDir: String, maxMemoryMb: Int) {
        val ctx = context ?: error("sing-box: контекст не инициализирован")
        if (!installed) install(ctx)
        stop()
        this.tunFd = tunFd
        // Для диагностики: конфиг, который реально получило ядро.
        // Внешний каталог — чтобы его можно было снять через adb.
        runCatching {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, "singbox.config.json").writeText(configJson)
        }
        val platform = Platform(tunFd)
        val created = Libbox.newService(configJson, platform)
        created.start()
        service = created
        running = true
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        runCatching { service?.close() }
        service = null
        running = false
        tunFd = 0
    }

    @JvmStatic
    @Synchronized
    fun startProbe(configJson: String, assetDir: String) {
        val ctx = context ?: error("sing-box: контекст не инициализирован")
        if (!installed) install(ctx)
        stopProbe()
        // У пробного конфига SOCKS-inbound: TUN не создаётся, fd не нужен.
        val created = Libbox.newService(configJson, Platform(0))
        created.start()
        probe = created
    }

    @JvmStatic
    @Synchronized
    fun stopProbe() {
        runCatching { probe?.close() }
        probe = null
    }

    @JvmStatic
    fun checkConfig(configJson: String) {
        val ctx = context ?: error("sing-box: контекст не инициализирован")
        if (!installed) install(ctx)
        Libbox.checkConfig(configJson)
    }

    /** Своя статистика: приложение вне туннеля, поэтому его байты — это трафик прокси. */
    @JvmStatic
    fun uplinkTotal(): Long = runCatching {
        TrafficStats.getUidTxBytes(Process.myUid()).toLong()
    }.getOrDefault(0L)

    @JvmStatic
    fun downlinkTotal(): Long = runCatching {
        TrafficStats.getUidRxBytes(Process.myUid()).toLong()
    }.getOrDefault(0L)

    /**
     * Дубликат fd без владения оригиналом: PFD.detachFd отдаёт дескриптор
     * Go-стороне, а мастер-дескриптор сервиса остаётся нетронутым.
     */
    private fun dupFd(fd: Int): Int {
        val source = java.io.FileDescriptor()
        val field = java.io.FileDescriptor::class.java.getDeclaredField("descriptor")
        field.isAccessible = true
        field.setInt(source, fd)
        return android.os.ParcelFileDescriptor.dup(source).detachFd()
    }

    /** Реализация PlatformInterface: минимум, необходимый sing-box на Android. */
    private class Platform(private val fd: Int) : PlatformInterface {
        private val tunnelTaken = AtomicBoolean(false)

        override fun openTun(options: TunOptions): Int {
            if (fd <= 0) error("sing-box: TUN не создан (fd=$fd)")
            // Ровно один раз: повторный openTun означает второй tun inbound.
            if (!tunnelTaken.compareAndSet(false, true)) error("sing-box: TUN уже открыт")
            // dup, чтобы закрытие fd ядром не затронуло мастер-дескриптор сервиса.
            return dupFd(fd)
        }

        override fun autoDetectInterfaceControl(fd: Int) = Unit

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = false

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): Int = -1

        override fun packageNameByUid(uid: Int): String? = null

        override fun uidByPackageName(packageName: String): Int = -1

        // Сознательно не отдаём sing-box сетевые интерфейсы: привязка сокета
        // к rmnet_data0/wlan0 на Android уводит его в собственный TUN и даёт
        // "i/o timeout". Приложение уже исключено из VPN, поэтому обычный
        // маршрут ОС — единственно правильный.
        override fun getInterfaces(): NetworkInterfaceIterator? = null

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit

        override fun readWIFIState(): WIFIState? = null

        override fun systemCertificates(): StringIterator? = null

        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun clearDNSCache() = Unit

        override fun sendNotification(notification: Notification) = Unit

        override fun writeLog(message: String) {
            val path = logPath ?: return
            runCatching {
                FileOutputStream(path, true).use { out ->
                    out.write(message.toByteArray())
                    out.write('\n'.code)
                }
            }
        }

        override fun useProcFS(): Boolean = false

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false
    }
}

