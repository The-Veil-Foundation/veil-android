package dev.local.veil.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DesktopSubscriptionFetcher : SubscriptionFetcher {
    private val idFile = File(System.getProperty("user.home"), ".veil/hwid.txt")

    override val deviceId: String
        get() {
            val stored = runCatching { idFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return regenerateDeviceId()
            val upper = stored.uppercase()
            return if (upper != stored) write(upper) else upper
        }

    override fun regenerateDeviceId(): String = write(dev.local.veil.core.DeviceIds.mint())

    override fun setDeviceId(raw: String): String? =
        dev.local.veil.core.DeviceIds.normalizedManual(raw)?.let(::write)

    private fun write(value: String): String {
        runCatching {
            idFile.parentFile?.mkdirs()
            idFile.writeText(value)
        }
        return value
    }

    private val uaFile = File(System.getProperty("user.home"), ".veil/user-agent.txt")

    override var userAgent: String
        get() = dev.local.veil.core.effectiveUserAgent(runCatching { uaFile.readText() }.getOrNull())
        set(value) {
            runCatching {
                val custom = value.trim()
                if (custom.isEmpty() || custom in dev.local.veil.core.LEGACY_DEFAULT_USER_AGENTS) {
                    uaFile.delete()
                } else {
                    uaFile.parentFile?.mkdirs()
                    uaFile.writeText(custom)
                }
            }
        }

    override suspend fun fetch(url: String, userAgent: String?, hwid: String?): Result<SubscriptionFetch> = withContext(Dispatchers.IO) {
        val agent = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: this@DesktopSubscriptionFetcher.userAgent
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", agent)
                if (hwid != null) setRequestProperty("X-Hwid", hwid)
                setRequestProperty("X-Device-OS", "Linux")
                setRequestProperty("X-Device-Model", System.getProperty("os.arch") ?: "desktop")
                setRequestProperty("X-Ver-OS", System.getProperty("os.version") ?: "")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("Подписка ответила HTTP $code")
                SubscriptionFetch(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
        }
    }
}
