package dev.local.veil.core

internal fun probeIntervalFor(subjects: Int): String =
    if (subjects > 8) "5m" else "3m"

internal fun probeIntervalSecondsFor(subjects: Int): Long =
    if (subjects > 8) 300 else 180
