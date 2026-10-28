package dev.local.veil.platform

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Загрузчик подписок.
 *
 * Remnawave (и совместимые панели) при включённом HWID отдают заглушку
 * «App not supported», пока клиент не пришлёт `X-Hwid` вместе с описанием
 * устройства. Идентификатор стабилен для установки: он же служит ключом
 * привязки устройства на панели.
 */
class AndroidSubscriptionFetcher(context: Context) : SubscriptionFetcher {

    private val prefs = context.getSharedPreferences("veil", Context.MODE_PRIVATE)

    override val deviceId: String by lazy {
        prefs.getString(KEY_HWID, null) ?: run {
            val generated = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(KEY_HWID, generated).apply()
            generated
        }
    }

    override var userAgent: String
        get() = prefs.getString(KEY_UA, null) ?: DEFAULT_UA
        set(value) {
            prefs.edit().putString(KEY_UA, value.ifBlank { DEFAULT_UA }).apply()
        }

    override suspend fun fetch(url: String): Result<SubscriptionFetch> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
                setRequestProperty("X-Hwid", deviceId)
                setRequestProperty("X-Device-OS", "Android")
                setRequestProperty("X-Device-Model", Build.MODEL ?: "Android")
                setRequestProperty("X-Ver-OS", Build.VERSION.RELEASE ?: "")
            }

            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Подписка ответила HTTP $code")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                SubscriptionFetch(body, parseUsage(connection.getHeaderField("subscription-userinfo")))
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * `subscription-userinfo: upload=0; download=1073741824; total=107374182400; expire=1767225600`
     * Так делают Remnawave, Marzban и Happ-совместимые панели.
     */
    private fun parseUsage(header: String?): SubscriptionUsage? {
        val raw = header?.takeIf { it.isNotBlank() } ?: return null
        val values = raw.split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=').trim().lowercase()
                val value = part.substringAfter('=', "").trim().toLongOrNull()
                if (key.isEmpty() || value == null) null else key to value
            }
            .toMap()
        if (values.isEmpty()) return null
        return SubscriptionUsage(
            upload = values["upload"] ?: 0L,
            download = values["download"] ?: 0L,
            total = values["total"] ?: 0L,
            expireAt = values["expire"] ?: 0L,
        )
    }

    private companion object {
        const val KEY_HWID = "hwid"
        const val KEY_UA = "user_agent"
        const val DEFAULT_UA = "Veil/0.1"
    }
}
