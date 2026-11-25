package dev.local.veil.platform

import dev.local.veil.core.ProxyConfig

enum class PingMode(val label: String, val hint: String) {
    TCP("TCP", "Только подключение к порту сервера: быстро, но цифры заниженные. Узлы на UDP не отвечают."),
    ICMP("ICMP", "Как команда ping. Многие серверы не отвечают на ICMP — тогда будет прочерк."),
    REAL("Реальный", "Запрос через сервер в интернет: то, что вы почувствуете в приложениях. Идёт по очереди, дольше."),
}

interface Pinger {

    suspend fun ping(server: ProxyConfig, timeoutMs: Int = 1500): Int?

    suspend fun icmp(server: ProxyConfig, timeoutMs: Int = 2000): Int? = null
}
