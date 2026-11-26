package dev.local.veil.core

object AddInput {

    sealed interface Kind {
        data object Empty : Kind

        data class Subscription(val url: String) : Kind {
            val host: String get() = url.substringAfter("://").substringBefore('/').substringBefore('?')
        }

        data class Servers(val body: SubscriptionPayload.Body.Ok) : Kind

        data class Invalid(val message: String) : Kind
    }

    fun classify(raw: String): Kind {

        val text = raw.filterNot { it in INVISIBLE }.trim()
        if (text.isEmpty()) return Kind.Empty

        if (!isSingleHttpUrl(text)) {
            val body = SubscriptionPayload.classify(text)
            if (body is SubscriptionPayload.Body.Ok && body.nodes.isNotEmpty()) return Kind.Servers(body)
        }

        subscriptionUrl(text)?.let { return Kind.Subscription(it) }

        wrappedSubscriptionUrl(text)?.let { return Kind.Subscription(it) }

        decodeBase64(text)?.trim()?.let { decoded -> subscriptionUrl(decoded)?.let { return Kind.Subscription(it) } }

        return Kind.Invalid("Not a link or subscription URL")
    }

    private val INVISIBLE = setOf('\uFEFF', '\u200B', '\u200C', '\u200D', '\u2060')

    internal fun wrappedSubscriptionUrl(text: String): String? {
        val schemeEnd = text.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = text.substring(0, schemeEnd).lowercase()
        if (scheme == "http" || scheme == "https") return null
        val rest = text.substring(schemeEnd + 3)

        val query = rest.substringBefore('#').substringAfter('?', "")
        if (query.isNotEmpty()) {
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=').lowercase()
                if (key != "url" && key != "sub" && key != "link") continue
                val value = percentDecode(pair.substringAfter('=', ""))
                subscriptionUrl(value.trim())?.let { return it }
            }
        }

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
