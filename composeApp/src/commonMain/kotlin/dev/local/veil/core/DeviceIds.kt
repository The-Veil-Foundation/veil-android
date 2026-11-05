package dev.local.veil.core

import kotlin.random.Random

/**
 * HWID для панелей — порт DeviceID.swift из veil-macos.
 *
 * Формат как на macOS: 16 hex-символов в верхнем регистре, например
 * `E0104A37B8464E6B`. 64 бита с запасом хватает на пару устройств аккаунта и
 * читается в списке устройств панели одной строкой.
 */
object DeviceIds {
    const val LENGTH = 16

    /** Новый случайный идентификатор. */
    fun mint(random: Random = Random.Default): String =
        buildString(LENGTH) { repeat(LENGTH) { append("0123456789ABCDEF"[random.nextInt(16)]) } }

    /**
     * Ручной ввод: пробелы и переводы строк по краям обрезаются (лишний пробел
     * в `X-Hwid` панель отвергнет), буквы — в верхний регистр, как у всех ID
     * Veil. Пусто — null.
     */
    fun normalizedManual(raw: String): String? = raw.trim().uppercase().takeIf { it.isNotEmpty() }
}
