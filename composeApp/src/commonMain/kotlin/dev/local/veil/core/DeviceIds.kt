package dev.local.veil.core

import kotlin.random.Random

object DeviceIds {
    const val LENGTH = 16

    fun mint(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append("0123456789ABCDEF"[random.nextInt(16)]) } }

    fun normalizedManual(raw: String): String? = raw.trim().uppercase().takeIf { it.isNotEmpty() }
}
