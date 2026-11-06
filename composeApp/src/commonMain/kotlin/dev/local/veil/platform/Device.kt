package dev.local.veil.platform

import androidx.compose.runtime.Composable

/**
 * Признак слабого устройства: мало RAM и, как правило, слабый GPU.
 *
 * На таких телефонах постоянные анимации (бесконечные лоадеры, морфинг форм,
 * вращающиеся бейджи) заметно съедают кадры. Платформа сообщает это честно,
 * вместо угадывания по модели.
 */
@Composable
expect fun isLowRamDevice(): Boolean
