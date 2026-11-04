package dev.local.veil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Совместимый слой для iOS-лэйаута: имена остались прежними, но визуал —
 * Material 3 Expressive. Никакого стекла и прозрачных бликов: тональные
 * поверхности, мягкая тень и стандартные формы.
 */

@Composable
internal fun Modifier.liquidGlass(
    cornerRadius: Dp,
    tint: Color = Color.Transparent,
    elevation: Dp = 0.dp,
    borderAlpha: Float = 1f,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    // Слишком прозрачный тон игнорируем: карточка должна оставаться читаемой.
    val fill = if (tint.alpha > 0.18f) tint else MaterialTheme.colorScheme.surfaceContainerLow
    var base = this
    if (elevation > 0.dp) {
        base = base.shadow(
            elevation = elevation.coerceAtMost(6.dp),
            shape = shape,
            clip = false,
        )
    }
    return base
        .clip(shape)
        .background(fill)
}

@Composable
internal fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    tint: Color = Color.Transparent,
    elevation: Dp = 0.dp,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlass(cornerRadius, tint, elevation),
        contentAlignment = contentAlignment,
        content = content,
    )
}

@Composable
internal fun GlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable BoxScope.() -> Unit,
) {
    // Без minimumInteractiveComponentSize: в плотной строке iOS-лэйаута
    // стандартные 48 dp перекрывают соседние элементы.
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
    Box(Modifier) { }
}

@Composable
internal fun HairlineDivider(startPadding: Dp = 16.dp) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(start = startPadding)
            .height(0.5.dp),
    ) {
        drawRect(color)
    }
}
