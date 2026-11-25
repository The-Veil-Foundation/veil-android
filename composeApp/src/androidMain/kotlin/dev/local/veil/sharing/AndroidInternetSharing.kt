package dev.local.veil.sharing

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import dev.local.veil.core.CoreEngine
import dev.local.veil.core.SharingConfig
import dev.local.veil.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import libbox.BoxService
import libbox.Libbox
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.Executors

class AndroidInternetSharing private constructor(private val context: Context) : InternetSharing {
    companion object {
        @Volatile private var instance: AndroidInternetSharing? = null
        fun get(context: Context): AndroidInternetSharing = instance ?: synchronized(this) {
            instance ?: AndroidInternetSharing(context.applicationContext).also { instance = it }
        }
        private fun secret(): String = ByteArray(18).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private val mutable = MutableStateFlow(SharingStatus())
    override val status = mutable.asStateFlow()
    private val shizukuState = MutableStateFlow(ShizukuStatus())
    override val shizuku = shizukuState.asStateFlow()
    private val binderReceived = Shizuku.OnBinderReceivedListener { refreshShizuku() }
    private val binderDead = Shizuku.OnBinderDeadListener { refreshShizuku() }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { request, _ ->
        if (request == 7103) refreshShizuku()
    }
    init {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
        refreshShizuku()
    }
    override fun refreshShizuku() {
        val running = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        shizukuState.value = ShizukuStatus(
            running = running,
            authorized = running && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false),
            root = running && runCatching { Shizuku.getUid() == 0 }.getOrDefault(false),
        )
    }
    override val shizukuSupported get() = Build.VERSION.SDK_INT >= 33
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + worker)
    private val main = Handler(Looper.getMainLooper())
    private val corePassword = secret()
    private val connected = MutableStateFlow(false)
    override val vpnConnected = connected.asStateFlow()
    @Volatile private var generation = 0
    private var bridge: BoxService? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var remote: ITetheringService? = null
    private var pending: CompletableDeferred<ITetheringService>? = null
    private var monitor: Job? = null
    private val lease = Binder()
    private val serviceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context, TetheringService::class.java))
            .daemon(false).processNameSuffix("sharing").version(1)
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service != null) {
                val bound = ITetheringService.Stub.asInterface(service)
                remote = bound
                pending?.complete(bound)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            pending?.completeExceptionally(IllegalStateException("Shizuku disconnected"))
            if (mutable.value.mode == SharingMode.SHIZUKU) {
                generation++
                scope.launch {
                    closeBridge()
                    mutable.value = SharingStatus(message = "Shizuku disconnected. Turn off the Android hotspot; traffic may bypass VPN.")
                }
            }
        }
    }

    fun attach(config: String, engine: CoreEngine): String = SharingConfig.attach(config, engine, corePassword)
    fun onCoreReady() { connected.value = true }
    fun onCoreStopping(reconnecting: Boolean = false) {
        connected.value = false
        if (reconnecting && mutable.value.active && !mutable.value.busy) return
        stop()
    }

    override fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { iface ->
            iface.isUp && !iface.isLoopback &&
                listOf("tun", "test", "rmnet", "ccmni", "pdp").none { iface.name.startsWith(it) }
        }.flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }.getOrDefault(emptyList())

    override fun openShizuku() {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: error("Install Shizuku first")
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { mutable.value = mutable.value.copy(message = error(it)) }
    }

    override fun authorizeShizuku() {
        refreshShizuku()
        runCatching {
            check(shizukuSupported) { "Shizuku sharing requires Android 13 or newer" }
            check(Shizuku.pingBinder()) { "Start Shizuku via USB ADB, wireless ADB or root first" }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(7103)
            else mutable.value = mutable.value.copy(message = "Shizuku permission granted")
        }.onFailure { mutable.value = mutable.value.copy(message = error(it)) }
    }

    override fun startProxy(address: String) = start(SharingMode.PROXY) { epoch ->
        check(address in addresses()) { "Enable the Android hotspot and select its local IP address" }
        stopResources()
        val password = secret().take(16)
        val created = createBridge(SharingConfig.bridge(corePassword, address, password), 0)
        bridge = created
        checkReady(epoch)
        mutable.value = SharingStatus(mode = SharingMode.PROXY, active = true, host = address, password = password)
    }

    override fun startShizuku() = start(SharingMode.SHIZUKU) { epoch ->
        check(shizukuSupported) { "Shizuku sharing requires Android 13 or newer" }
        check(Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            "Start Shizuku and grant Veil permission first"
        }
        stopResources()
        val service = bind()
        val fd = service.prepare(lease)
        descriptor = fd
        bridge = createBridge(SharingConfig.bridge(corePassword), fd.fd)
        checkReady(epoch)
        service.activate()
        checkReady(epoch)
        mutable.value = SharingStatus(mode = SharingMode.SHIZUKU, active = true, message = "Experimental: test-network upstream confirmed")
        monitor = scope.launch {
            while (isActive && mutable.value.mode == SharingMode.SHIZUKU) {
                delay(1000)
                val result = runCatching { service.status() }.getOrElse { "ERROR: Shizuku disconnected" }
                if (result != "ACTIVE") {
                    runCatching { stopResources() }
                    mutable.value = SharingStatus(message = "$result. Check that the Android hotspot is off.")
                    break
                }
            }
        }
    }

    private fun start(mode: SharingMode, action: suspend (Int) -> Unit) {
        if (mutable.value.busy) return
        val epoch = ++generation
        mutable.value = SharingStatus(mode = mode, busy = true)
        scope.launch {
            runCatching { checkReady(epoch); action(epoch) }.onFailure { failure ->
                val cleanup = runCatching { stopResources() }.exceptionOrNull()
                mutable.value = SharingStatus(message = error(failure) + if (cleanup != null) ". Turn off the hotspot in Android settings." else "")
            }
        }
    }

    private fun checkReady(epoch: Int) {
        check(connected.value && epoch == generation) { "VPN must be connected. Sharing stopped; start it again after reconnecting." }
    }

    private fun createBridge(config: String, fd: Int): BoxService {
        singbox.Singbox.install(context)
        val service = Libbox.newService(config, singbox.Singbox.Platform(fd))
        try { service.start(); return service } catch (error: Throwable) { runCatching { service.close() }; throw error }
    }

    private suspend fun bind(): ITetheringService {
        remote?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val ready = CompletableDeferred<ITetheringService>()
        pending = ready
        main.post { runCatching { Shizuku.bindUserService(serviceArgs, connection) }.onFailure { ready.completeExceptionally(it) } }
        return try { withTimeout(15_000) { ready.await() } } finally { pending = null }
    }

    override fun stop() {
        generation++
        if (!mutable.value.active && !mutable.value.busy && bridge == null && remote == null) return
        mutable.value = mutable.value.copy(busy = true, active = false, password = "")
        scope.launch {
            val failure = runCatching { stopResources() }.exceptionOrNull()
            mutable.value = SharingStatus(message = failure?.let { "${error(it)}. Turn off the hotspot in Android settings." }.orEmpty())
        }
    }

    private fun closeBridge() {
        runCatching { bridge?.close() }
        bridge = null
        runCatching { descriptor?.close() }
        descriptor = null
    }

    private fun stopResources() {
        monitor?.cancel(); monitor = null
        // Keep the remote test TUN as a sink when Android refuses to stop tethering.
        val failure = runCatching { remote?.stop() }.exceptionOrNull()
        closeBridge()
        if (failure != null) throw failure
    }

    override fun openHotspotSettings() {
        runCatching {
            context.startActivity(Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.recoverCatching {
            context.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { mutable.value = mutable.value.copy(message = error(it)) }
    }
    private fun error(t: Throwable): String = (t.cause ?: t).message ?: t.javaClass.simpleName
}
