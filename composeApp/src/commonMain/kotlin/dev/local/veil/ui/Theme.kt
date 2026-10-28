package dev.local.veil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
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

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFCFBCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B292F),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
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
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(32.dp),
    extraLarge = RoundedCornerShape(44.dp),
)

internal fun orbitTypography() = Typography()
