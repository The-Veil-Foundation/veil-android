package dev.local.veil.platform

import dev.local.veil.core.ProxyConfig

/**
 * Как меряем задержку.
 *
 * TCP показывает только время рукопожатия с портом сервера — без шифрования
 * и протокола, поэтому цифры заниженные. ICMP — то же, что команда `ping`.
 * REAL — запрос через ядро и сервер до внешнего сайта: ровно то, что
 * чувствуется в приложениях.
 */
enum class PingMode(val label: String, val hint: String) {
    TCP("TCP", "Только подключение к порту сервера: быстро, но цифры заниженные. Узлы на UDP не отвечают."),
    ICMP("ICMP", "Как команда ping. Многие серверы не отвечают на ICMP — тогда будет прочерк."),
    REAL("Реальный", "Запрос через сервер в интернет: то, что вы почувствуете в приложениях. Идёт по очереди, дольше."),
}

/** Задержка до сервера в миллисекундах; null — недоступен. */
interface Pinger {
    /** TCP-подключение к адресу сервера. */
    suspend fun ping(server: ProxyConfig, timeoutMs: Int = 1500): Int?

    /** ICMP echo, как обычный `ping`; null — нет ответа или не поддерживается. */
    suspend fun icmp(server: ProxyConfig, timeoutMs: Int = 2000): Int? = null
}
