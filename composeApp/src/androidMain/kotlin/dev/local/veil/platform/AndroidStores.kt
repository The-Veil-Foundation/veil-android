package dev.local.veil.platform

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import dev.local.veil.core.AppSettings
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Socket

class AndroidSettingsStore(context: Context) : SettingsStore {
    private val prefs = context.getSharedPreferences("veil", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): AppSettings = runCatching {
        prefs.getString(KEY, null)?.let { json.decodeFromString(AppSettings.serializer(), it) }
    }.getOrNull() ?: AppSettings()

    override fun save(settings: AppSettings) {
        prefs.edit().putString(KEY, json.encodeToString(AppSettings.serializer(), settings)).apply()
    }

    private companion object {
        const val KEY = "settings_v2"
    }
}

class AndroidPackageLister(context: Context) : PackageLister {
    private val packageManager = context.packageManager
    private val selfPackage = context.packageName

    override suspend fun installedApps(): List<AppEntry> = withContext(Dispatchers.IO) {
        val apps: List<ApplicationInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        apps.asSequence()
            // только то, что пользователь может запустить
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .filter { it.packageName != selfPackage }
            .map {
                AppEntry(
                    packageName = it.packageName,
                    label = packageManager.getApplicationLabel(it).toString(),
                    isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }
}

class AndroidPinger : Pinger {
    override suspend fun ping(server: ProxyConfig, timeoutMs: Int): Int? = withContext(Dispatchers.IO) {
        val started = System.nanoTime()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(server.address, server.port), timeoutMs)
            }
            ((System.nanoTime() - started) / 1_000_000L).toInt()
        }.getOrNull()
    }
}

/** Подписки в том же SharedPreferences, что и серверы. */
class AndroidSubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("veil", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(Subscription.serializer())

    fun load(): List<Subscription> = runCatching {
        prefs.getString("subscriptions", null)?.let { json.decodeFromString(serializer, it) }
    }.getOrNull().orEmpty()

    fun save(items: List<Subscription>) {
        prefs.edit().putString("subscriptions", json.encodeToString(serializer, items)).apply()
    }
}
