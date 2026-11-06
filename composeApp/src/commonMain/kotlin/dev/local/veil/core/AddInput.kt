package dev.local.veil.core

/**
 * Что пользователь вставил или отсканировал. Порт AddInput.swift из veil-macos:
 * выбирать «подписка или ссылка» не нужно — лист сам понимает, что это.
 */
object AddInput {

    sealed interface Kind {
        data object Empty : Kind

        /** Адрес подписки, который ещё нужно скачать. */
        data class Subscription(val url: String) : Kind {
            val host: String get() = url.substringAfter("://").substringBefore('/').substringBefore('?')
        }

        /** Ссылки узлов, wg-quick или тело подписки целиком — качать нечего. */
        data class Servers(val body: SubscriptionPayload.Body.Ok) : Kind

        data class Invalid(val message: String) : Kind
    }

    fun classify(raw: String): Kind {
        // QR и буфер обмена иногда приносят BOM и zero-width символы, trim() их не видит.
        val text = raw.filterNot { it in INVISIBLE }.trim()
        if (text.isEmpty()) return Kind.Empty

        // Ссылки узлов и тела подписок живут в других схемах, чем http(s),
        // поэтому сначала они: адрес подписки — только если узлов не нашлось.
        if (!isSingleHttpUrl(text)) {
            val body = SubscriptionPayload.classify(text)
            if (body is SubscriptionPayload.Body.Ok && body.nodes.isNotEmpty()) return Kind.Servers(body)
        }

        subscriptionUrl(text)?.let { return Kind.Subscription(it) }

        // QR из панелей (Remnawave, Marzban, 3x-ui) часто — диплинк клиента
        // с адресом подписки внутри: happ://add/https://…, v2raytun://import/…,
        // clash://install-config?url=…, sing-box://import-remote-profile?url=…
        wrappedSubscriptionUrl(text)?.let { return Kind.Subscription(it) }

        // Некоторые панели выдают адрес подписки в base64.
        decodeBase64(text)?.trim()?.let { decoded -> subscriptionUrl(decoded)?.let { return Kind.Subscription(it) } }

        return Kind.Invalid("Not a link or subscription URL")
    }

    private val INVISIBLE = setOf('\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060')

    /** Адрес подписки из диплинка другого клиента, если он там есть. */
    internal fun wrappedSubscriptionUrl(text: String): String? {
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme == "http" || scheme == "https") return null
        val rest = text.substring(schemeEnd + 3)

        // ?url=… / &url=… (обычно percent-encoded)
        val query = rest.substringBefore('#').substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=').lowercase()
                if (key != "url" && key != "sub" && key != "link") continue
                val value = percentDecode(pair.substringAfter('=', ""))
                subscriptionUrl(value.trim())?.let { return it }
            }
        }

        // add/https://…, import/https%3A%2F%2F…, или сразу https://… после схемы
        val path = rest.substringBefore('#')
        val candidates = listOf(path, percentDecode(path))
        for (candidate in candidates) {
            val at = candidate.indexOf("http", ignoreCase = true)
            if (at < 0) continue
            val inner = candidate.substring(at)
            subscriptionUrl(inner)?.let { return it }
        }
        return null
    }

    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length + 1) {
                val hex = value.substring(i + 1, minOf(i + 3, value.length)).takeIf { it.length == 2 }?.toIntOrNull(16)
                if (hex != null) {
                    bytes.add(hex.toByte())
                    i += 3
                    continue
                }
            }
            c.toString().encodeToByteArray().forEach { bytes.add(it) }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun isSingleHttpUrl(text: String): Boolean = subscriptionUrl(text) != null

    private fun subscriptionUrl(text: String): String? {
        if (text.any { it == '\n' || it == '\r' || it == ' ' }) return null
        val lower = text.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        val host = text.substringAfter("://").substringBefore('/').substringBefore('?')
        if (host.isBlank()) return null
        return text
    }

    private fun decodeBase64(text: String): String? {
        if (text.length < 12 || text.any { it.isWhitespace() }) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/-_="
        if (!text.all { it in alphabet }) return null
        return runCatching {
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            val normalized = text.replace('-', '+').replace('_', '/').trimEnd('=')
            val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
            kotlin.io.encoding.Base64.decode(padded).decodeToString()
        }.getOrNull()
    }
}
