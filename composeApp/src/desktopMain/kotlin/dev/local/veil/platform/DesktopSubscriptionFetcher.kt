package dev.local.veil.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class DesktopSubscriptionFetcher : SubscriptionFetcher {
    private val idFile = File(System.getProperty("user.home"), ".veil/hwid.txt")

    override val deviceId: String by lazy {
        runCatching { idFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
            val generated = UUID.randomUUID().toString().replace("-", "").take(16)
            runCatching {
                idFile.parentFile?.mkdirs()
                idFile.writeText(generated)
            }
            generated
        }
    }

    private val uaFile = File(System.getProperty("user.home"), ".veil/user-agent.txt")

    override var userAgent: String
        get() = runCatching { uaFile.readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "Veil/0.1"
        set(value) {
            runCatching {
                uaFile.parentFile?.mkdirs()
                uaFile.writeText(value.ifBlank { "Veil/0.1" })
            }
        }

    override suspend fun fetch(url: String): Result<SubscriptionFetch> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("X-Hwid", deviceId)
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
