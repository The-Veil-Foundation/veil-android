package dev.local.veil.platform

import dev.local.veil.core.ProxyConfig

/** Задержка до сервера в миллисекундах; null — недоступен. */
interface Pinger {
    suspend fun ping(server: ProxyConfig, timeoutMs: Int = 1500): Int?
}
