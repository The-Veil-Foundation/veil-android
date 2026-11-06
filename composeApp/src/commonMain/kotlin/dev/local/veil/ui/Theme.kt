package dev.local.veil.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.veil.core.AppStyle

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

    // Общая тема для Android и остальных платформ проекта.
    MaterialTheme(
        colorScheme = animatedColorScheme(scheme),
        typography = orbitTypography(),
        shapes = orbitShapes(),
        content = content,
    )
}

/**
 * Плавная смена темы: каждый цвет схемы перетекает к новому значению, а не
 * переключается кадром. На слабых устройствах — мгновенно.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec: androidx.compose.animation.core.AnimationSpec<androidx.compose.ui.graphics.Color> =
        if (dev.local.veil.platform.isLowRamDevice()) {
            androidx.compose.animation.core.snap()
        } else {
            androidx.compose.animation.core.tween(
                durationMillis = 200,
                easing = androidx.compose.animation.core.LinearOutSlowInEasing,
            )
        }

    @Composable
    fun animate(color: androidx.compose.ui.graphics.Color) =
        androidx.compose.animation.animateColorAsState(color, spec, label = "theme").value

    return target.copy(
        primary = animate(target.primary),
        onPrimary = animate(target.onPrimary),
        primaryContainer = animate(target.primaryContainer),
        onPrimaryContainer = animate(target.onPrimaryContainer),
        inversePrimary = animate(target.inversePrimary),
        secondary = animate(target.secondary),
        onSecondary = animate(target.onSecondary),
        secondaryContainer = animate(target.secondaryContainer),
        onSecondaryContainer = animate(target.onSecondaryContainer),
        tertiary = animate(target.tertiary),
        onTertiary = animate(target.onTertiary),
        tertiaryContainer = animate(target.tertiaryContainer),
        onTertiaryContainer = animate(target.onTertiaryContainer),
        background = animate(target.background),
        onBackground = animate(target.onBackground),
        surface = animate(target.surface),
        onSurface = animate(target.onSurface),
        surfaceVariant = animate(target.surfaceVariant),
        onSurfaceVariant = animate(target.onSurfaceVariant),
        surfaceTint = animate(target.surfaceTint),
        inverseSurface = animate(target.inverseSurface),
        inverseOnSurface = animate(target.inverseOnSurface),
        error = animate(target.error),
        onError = animate(target.onError),
        errorContainer = animate(target.errorContainer),
        onErrorContainer = animate(target.onErrorContainer),
        outline = animate(target.outline),
        outlineVariant = animate(target.outlineVariant),
        scrim = animate(target.scrim),
        surfaceBright = animate(target.surfaceBright),
        surfaceDim = animate(target.surfaceDim),
        surfaceContainer = animate(target.surfaceContainer),
        surfaceContainerHigh = animate(target.surfaceContainerHigh),
        surfaceContainerHighest = animate(target.surfaceContainerHighest),
        surfaceContainerLow = animate(target.surfaceContainerLow),
        surfaceContainerLowest = animate(target.surfaceContainerLowest),
    )
}

/** Материаловские скругления из редизайна: крупные радиусы, мягкие формы. */
internal fun orbitShapes() = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Эмфатическая типографика M3 Expressive: заголовки и подписи плотнее и жирнее,
 * чтобы акценты читались сильнее, чем в базовой шкале.
 */
internal fun orbitTypography(): Typography {
    val base = Typography()
    // M3 Expressive: акцентная (emphasized) шкала — те же размеры, но
    // плотнее трекинг и тяжелее вес для заголовков и подписей.
    return base.copy(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    )
}
