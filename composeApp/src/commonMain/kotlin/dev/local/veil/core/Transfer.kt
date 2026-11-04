package dev.local.veil.core

import kotlinx.serialization.Serializable

/**
 * Что переносим между устройствами: ссылки подписок и share-ссылки
 * серверов, добавленных вручную.
 */
@Serializable
data class TransferPayload(
    val subscriptions: List<String> = emptyList(),
    val servers: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = subscriptions.isEmpty() && servers.isEmpty()
}

object Pairing {
    /**
     * Адрес приёма из QR на приставке: только локальная сеть и ровно такой
     * вид. Так поддельный QR не заставит телефон отправить подписки на
     * чужой сервер в интернете.
     */
    private val PATTERN = Regex(
        """^http://(10\.\d{1,3}\.\d{1,3}\.\d{1,3}|192\.168\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}):(\d{1,5})/pair/[0-9a-f]{32}$""",
    )

    fun parse(text: String): String? = text.trim().takeIf { PATTERN.matches(it) }

    /** Текст из поля ввода на странице приёма: http(s) — подписки, остальные ссылки — серверы. */
    fun fromText(text: String): TransferPayload {
        val lines = text.lineSequence().map { it.trim() }.filter { it.contains("://") }.toList()
        val (subs, servers) = lines.partition { it.startsWith("http://") || it.startsWith("https://") }
        return TransferPayload(subscriptions = subs.distinct(), servers = servers.distinct())
    }
}
