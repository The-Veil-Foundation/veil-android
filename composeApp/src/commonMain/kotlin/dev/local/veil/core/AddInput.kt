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
        val text = raw.trim()
        if (text.isEmpty()) return Kind.Empty

        // Ссылки узлов и тела подписок живут в других схемах, чем http(s),
        // поэтому сначала они: адрес подписки — только если узлов не нашлось.
        if (!isSingleHttpUrl(text)) {
            val body = SubscriptionPayload.classify(text)
            if (body is SubscriptionPayload.Body.Ok && body.nodes.isNotEmpty()) return Kind.Servers(body)
        }

        subscriptionUrl(text)?.let { return Kind.Subscription(it) }

        // Некоторые панели выдают адрес подписки в base64.
        decodeBase64(text)?.trim()?.let { decoded -> subscriptionUrl(decoded)?.let { return Kind.Subscription(it) } }

        return Kind.Invalid("Not a link or subscription URL")
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
