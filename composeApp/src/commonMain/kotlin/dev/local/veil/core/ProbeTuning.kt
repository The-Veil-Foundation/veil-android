package dev.local.veil.core

/**
 * Проба балансировщика — это отдельный HTTPS-запрос на каждый узел группы.
 * Чем больше узлов, тем реже их стоит проверять: иначе радио просыпается
 * каждую минуту и тратит заряд, хотя выбор узла так часто не меняется.
 */
internal fun probeIntervalFor(subjects: Int): String =
    if (subjects > 8) "5m" else "3m"

internal fun probeIntervalSecondsFor(subjects: Int): Long =
    if (subjects > 8) 300 else 180
