package dev.local.veil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import dev.local.veil.core.AppStyle
import androidx.compose.ui.unit.dp

/**
 * Material You: на Android 12+ палитра берётся из обоев пользователя,
 * на десктопе и старых версиях — резервная фиолетовая схема.
 */
@Composable
expect fun dynamicColorScheme(darkTheme: Boolean): ColorScheme?

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF22005D),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFEF7FF),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFEF7FF),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
)

/**
 * Тёмная палитра в духе ночных настроек One UI: глубокий сине-серый фон,
 * карточки на тон светлее и спокойный голубой акцент. В отличие от
 * фиолетового M3 по умолчанию, такой интерфейс меньше «светится» ночью.
 */
private val FallbackDark = darkColorScheme(
    primary = Color(0xFFA9C7FF),
    onPrimary = Color(0xFF0B2B52),
    primaryContainer = Color(0xFF23446F),
    onPrimaryContainer = Color(0xFFD7E4FF),
    secondary = Color(0xFFB9C8DA),
    onSecondary = Color(0xFF233240),
    secondaryContainer = Color(0xFF233240),
    onSecondaryContainer = Color(0xFFD5E3F5),
    tertiary = Color(0xFF9FD0C9),
    tertiaryContainer = Color(0xFF1F4441),
    onTertiaryContainer = Color(0xFFC3EFE8),
    background = Color(0xFF0B1118),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF0B1118),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF2A3542),
    onSurfaceVariant = Color(0xFFA9B7C6),
    surfaceContainerLowest = Color(0xFF070C12),
    surfaceContainerLow = Color(0xFF111923),
    surfaceContainer = Color(0xFF141D28),
    surfaceContainerHigh = Color(0xFF18222E),
    surfaceContainerHighest = Color(0xFF1E2A38),
    outline = Color(0xFF7C8B9C),
    outlineVariant = Color(0xFF2A3644),
)

/**
 * Pitch Black: тот же акцент, но фон и поверхности — настоящий чёрный.
 * На OLED такие пиксели выключены, поэтому экран не светится в темноте.
 */
private fun pitchBlackScheme(base: ColorScheme): ColorScheme = base.copy(
    background = Color(0xFF000000),
    onBackground = Color(0xFFEDE9F4),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF151318),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF07070A),
    surfaceContainer = Color(0xFF0C0B10),
    surfaceContainerHigh = Color(0xFF131218),
    surfaceContainerHighest = Color(0xFF1A181F),
    outlineVariant = Color(0xFF26232D),
)

/**
 * Режим разметки. iOS-лэйаут включён, но сами компоненты остаются
 * Material 3 Expressive: цвета, формы и контролы берутся из темы.
 */
val LocalAppStyle = staticCompositionLocalOf { AppStyle.IOS }

@Composable
fun OrbitTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pitchBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = darkTheme || pitchBlack
    val base = dynamicColorScheme(dark) ?: if (dark) FallbackDark else FallbackLight
    val scheme = if (pitchBlack) pitchBlackScheme(base) else base

    MaterialTheme(
        colorScheme = scheme,
        typography = orbitTypography(),
        shapes = orbitShapes(),
        content = content,
    )
}

/** Материаловские скругления из редизайна: крупные радиусы, мягкие формы. */
internal fun orbitShapes() = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Эмфатическая типографика M3 Expressive: заголовки и подписи плотнее и жирнее,
 * чтобы акценты читались сильнее, чем в базовой шкале.
 */
internal fun orbitTypography(): Typography {
    val base = Typography()
    return base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}
