package dev.local.veil.platform

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AndroidSubscriptionFetcher(context: Context) : SubscriptionFetcher {

    private val prefs = context.getSharedPreferences("veil", Context.MODE_PRIVATE)

    override val deviceId: String
        get() {
            val stored = prefs.getString(KEY_HWID, null)?.takeIf { it.isNotBlank() } ?: return regenerateDeviceId()
            val upper = stored.uppercase()
            if (upper != stored) prefs.edit().putString(KEY_HWID, upper).apply()
            return upper
        }

    override fun regenerateDeviceId(): String {
        val minted = dev.local.veil.core.DeviceIds.mint()
        prefs.edit().putString(KEY_HWID, minted).apply()
        return minted
    }

    override fun setDeviceId(raw: String): String? {
        val value = dev.local.veil.core.DeviceIds.normalizedManual(raw) ?: return null
        prefs.edit().putString(KEY_HWID, value).apply()
        return value
    }

    override var userAgent: String
        get() = dev.local.veil.core.effectiveUserAgent(prefs.getString(KEY_UA, null))
        set(value) {

            val custom = value.trim().takeUnless { it.isEmpty() || it in dev.local.veil.core.LEGACY_DEFAULT_USER_AGENTS }
            prefs.edit().apply { if (custom == null) remove(KEY_UA) else putString(KEY_UA, custom) }.apply()
        }

    override suspend fun fetch(url: String, userAgent: String?, hwid: String?): Result<SubscriptionFetch> = withContext(Dispatchers.IO) {
        val agent = userAgent?.trim()?.takeIf { it.isNotEmpty() } ?: this@AndroidSubscriptionFetcher.userAgent
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", agent)
                setRequestProperty("Accept", "*/*")
                if (hwid != null) setRequestProperty("X-Hwid", hwid)
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
                SubscriptionFetch(
                    body = body,
                    usage = parseUsage(connection.getHeaderField("subscription-userinfo")),
                    title = decodeHeader(connection.getHeaderField("profile-title")),
                    description = decodeHeader(connection.getHeaderField("announce")),
                    supportUrl = connection.getHeaderField("support-url").orEmpty(),
                    webUrl = connection.getHeaderField("profile-web-page-url").orEmpty(),
                )
            } finally {
                connection.disconnect()
            }
        }
    }

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

    private fun decodeHeader(raw: String?): String {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return ""
        val prefix = "base64:"
        val hasPrefix = value.startsWith(prefix, ignoreCase = true)
        val payload = if (hasPrefix) value.substring(prefix.length).trim() else value
        if (payload.isEmpty()) return ""
        val decoded = runCatching {
            val normalized = payload
                .replace('-', '+')
                .replace('_', '/')
                .replace("\n", "")
                .replace("\r", "")
            val padded = when (normalized.length % 4) {
                2 -> normalized + "=="
                3 -> normalized + "="
                0 -> normalized
                else -> return ""
            }
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()

        if (decoded == null) return if (hasPrefix) "" else value
        if (decoded.isBlank()) return if (hasPrefix) "" else value
        val printable = decoded.count { it == '\n' || it == '\r' || it == '\t' || it.code in 32..126 || it.code > 127 }
        if (printable != decoded.length) return if (hasPrefix) "" else value
        return decoded
    }

    private companion object {
        const val KEY_HWID = "hwid"
        const val KEY_UA = "user_agent"
    }
}
