package dev.local.veil.platform

import androidx.compose.runtime.Composable

/**
 * Системная кнопка/жест «назад». На Android перехватывает событие, чтобы
 * из вложенного экрана возвращаться внутрь приложения, а не закрывать его.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
