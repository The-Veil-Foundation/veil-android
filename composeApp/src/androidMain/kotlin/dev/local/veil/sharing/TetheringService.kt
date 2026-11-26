package dev.local.veil.sharing

import android.content.Context
import android.net.*
import android.os.*
import dev.local.veil.core.SharingConfig
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Runs as Shizuku's shell UID. No root commands or global firewall changes. */
class TetheringService(context: Context) : ITetheringService.Stub() {
    private val context = ShellContextCompat.create(context)
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val tethering by lazy { checkNotNull(this.context.getSystemService("tethering")) }
    private val testManager by lazy { checkNotNull(this.context.getSystemService("test_network")) }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val direct = Executor { it.run() }
    private var tun: ParcelFileDescriptor? = null
    private var testInterface: Any? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var tetherCallback: Any? = null
    private var network: Network? = null
    private var lease: IBinder? = null
    private val lifetime = Binder()
    private var changedPreference = false
    private val death = IBinder.DeathRecipient { executor.execute { runCatching { stop() } } }
    @Volatile private var interfaceName = ""
    @Volatile private var upstream = ""
    @Volatile private var hotspot = false
    @Volatile private var armed = false
    @Volatile private var state = "OFF"

    override fun status(): String = state

    private fun sharingLinkAddress(cidr: String): LinkAddress = LinkAddress::class.java
        .getConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
        .newInstance(InetAddress.getByName(cidr.substringBefore('/')), cidr.substringAfter('/').toInt())

    @Synchronized override fun prepare(lease: IBinder): ParcelFileDescriptor {
        check(Build.VERSION.SDK_INT >= 33) { "Android 13+ required" }
        check(tun == null) { "Sharing already prepared; stop it first" }
        try {
            observeTethering()
            check(!hotspot) { "Turn off the existing Wi-Fi hotspot before starting Shizuku sharing" }
            this.lease = lease
            lease.linkToDeath(death, 0)
            val addresses = arrayOf(sharingLinkAddress(SharingConfig.IPV4), sharingLinkAddress(SharingConfig.IPV6))
            testInterface = testManager.javaClass.getMethod("createTunInterface", addresses.javaClass)
                .invoke(testManager, addresses as Any)
            val iface = checkNotNull(testInterface)
            tun = iface.javaClass.getMethod("getFileDescriptor").invoke(iface) as ParcelFileDescriptor
            interfaceName = iface.javaClass.getMethod("getInterfaceName").invoke(iface) as String
            state = "PREPARED"
            return ParcelFileDescriptor.dup(tun!!.fileDescriptor)
        } catch (error: Throwable) {
            cleanup()
            state = "ERROR: ${message(error)}"
            throw IllegalStateException(message(error))
        }
    }

    @Synchronized override fun activate() {
        check(tun != null && state == "PREPARED") { "Prepare the tunnel first" }
        try {
            val published = CountDownLatch(1)
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { capture(n, connectivity.getLinkProperties(n)) }
                override fun onLinkPropertiesChanged(n: Network, p: LinkProperties) { capture(n, p) }
                private fun capture(n: Network, p: LinkProperties?) {
                    if (p?.interfaceName == interfaceName) { network = n; published.countDown() }
                }
                override fun onLost(n: Network) {
                    if (network == n && armed) fail("Test network lost")
                }
            }
            networkCallback = callback
            val request = NetworkRequest.Builder().addTransportType(7)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
            connectivity.requestNetwork(request, callback)
            val properties = LinkProperties().apply {
                interfaceName = this@TetheringService.interfaceName
                setLinkAddresses(listOf(sharingLinkAddress(SharingConfig.IPV4), sharingLinkAddress(SharingConfig.IPV6)))
                setDnsServers(listOf(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("2606:4700:4700::1111")))
                mtu = 1500
            }
            testManager.javaClass.getMethod("setupTestNetwork", LinkProperties::class.java,
                Boolean::class.javaPrimitiveType, IBinder::class.java).invoke(testManager, properties, true, lifetime)
            check(published.await(10, TimeUnit.SECONDS)) { "Android did not publish the test network" }
            prefer(true)
            changedPreference = true
            armed = true
            state = "STARTING"
            startHotspot()
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (System.nanoTime() < end && state == "STARTING") {
                if (hotspot && upstream == interfaceName) { state = "ACTIVE"; break }
                Thread.sleep(100)
            }
            check(state == "ACTIVE") { "Hotspot did not select the VPN test network" }
        } catch (error: Throwable) {
            val reason = message(error)
            runCatching { stop() }
            state = "ERROR: $reason"
            throw IllegalStateException(reason)
        }
    }

    private fun observeTethering() {
        val received = CountDownLatch(1)
        val clazz = Class.forName("android.net.TetheringManager\$TetheringEventCallback")
        check(clazz.isInterface) { "Unsupported tethering callback API" }
        val callback = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(clazz)) { proxy, method, args ->
            when (method.name) {
                "onUpstreamChanged" -> {
                    upstream = (args?.firstOrNull() as? Network)?.let { connectivity.getLinkProperties(it)?.interfaceName }.orEmpty()
                    if (armed && hotspot && upstream.isNotEmpty() && upstream != interfaceName) fail("Android switched hotspot outside VPN")
                    null
                }
                "onTetheredInterfacesChanged" -> {
                    val interfaces = args?.firstOrNull() as? Set<*>
                    if (interfaces != null) {
                        hotspot = interfaces.any { it != null && it.javaClass.getMethod("getType").invoke(it) == 0 }
                        received.countDown()
                        if (armed && hotspot && upstream.isNotEmpty() && upstream != interfaceName) fail("Hotspot selected a direct upstream")
                        if (armed && state == "ACTIVE" && !hotspot) fail("Hotspot was turned off")
                    }
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                "toString" -> "Veil tethering observer"
                else -> null
            }
        }
        tetherCallback = callback
        tethering.javaClass.getMethod("registerTetheringEventCallback", Executor::class.java, clazz)
            .invoke(tethering, direct, callback)
        check(received.await(4, TimeUnit.SECONDS)) { "Cannot monitor hotspot state on this firmware" }
    }

    private fun startHotspot() {
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
        val request = builderClass.getMethod("build").invoke(builder)
        val cbClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        check(cbClass.isInterface)
        val done = CountDownLatch(1)
        var failure: String? = null
        val cb = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(cbClass)) { proxy, method, args ->
            when (method.name) {
                "onTetheringStarted" -> { done.countDown(); null }
                "onTetheringFailed" -> { failure = "Android hotspot error ${args?.firstOrNull()}"; done.countDown(); null }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> null
            }
        }
        tethering.javaClass.getMethod("startTethering", request.javaClass, Executor::class.java, cbClass)
            .invoke(tethering, request, direct, cb)
        check(done.await(10, TimeUnit.SECONDS)) { "Hotspot start timed out" }
        check(failure == null) { failure!! }
    }

    private fun fail(reason: String) {
        if (!armed) return
        armed = false
        state = "ERROR: $reason"
        executor.execute {
            runCatching { stop() }
            state = "ERROR: $reason"
        }
    }

    /** Stop Wi-Fi forwarding before releasing the synthetic upstream. */
    @Synchronized override fun stop() {
        armed = false
        if (tun != null) {
            tethering.javaClass.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tethering, 0)
            val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (hotspot && System.nanoTime() < end) Thread.sleep(100)
            if (hotspot) {
                state = "ERROR: Cannot stop hotspot; turn it off in Android settings"
                // Keep the TUN alive as a sink rather than deliberately selecting a direct upstream.
                executor.schedule({ runCatching { stop() } }, 2, TimeUnit.SECONDS)
                throw IllegalStateException(state)
            }
        }
        cleanup()
        state = "OFF"
    }

    private fun prefer(value: Boolean) {
        tethering.javaClass.getMethod("setPreferTestNetworks", Boolean::class.javaPrimitiveType).invoke(tethering, value)
    }

    private fun cleanup() {
        if (changedPreference) runCatching { prefer(false) }
        changedPreference = false
        network?.let { n -> runCatching { testManager.javaClass.getMethod("teardownTestNetwork", Network::class.java).invoke(testManager, n) } }
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        tetherCallback?.let { cb -> runCatching {
            tethering.javaClass.getMethod("unregisterTetheringEventCallback", Class.forName("android.net.TetheringManager\$TetheringEventCallback"))
                .invoke(tethering, cb)
        } }
        runCatching { tun?.close() }
        runCatching { lease?.unlinkToDeath(death, 0) }
        tun = null; testInterface = null; network = null; networkCallback = null; tetherCallback = null; lease = null
        interfaceName = ""; upstream = ""; hotspot = false
    }

    override fun destroy() { runCatching { stop() }; if (tun == null) { executor.shutdownNow(); kotlin.system.exitProcess(0) } }
    private fun message(t: Throwable): String = (t.cause ?: t).message ?: t.javaClass.simpleName
}
