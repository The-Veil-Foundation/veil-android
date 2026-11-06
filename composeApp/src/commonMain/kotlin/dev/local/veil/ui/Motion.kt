package dev.local.veil.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Material 3 Expressive motion: быстрые пружины с живым отскоком (stiffness=800+).
 * Эффект присутствия, но отклик мгновенный.
 *
 * [LocalReduceMotion] включается на слабых устройствах: анимации появления и
 * постоянные морфы там только роняют кадры, поэтому они выключаются целиком.
 */

internal val LocalReduceMotion = staticCompositionLocalOf { false }

/**
 * Токены движения из Material 3 Expressive (motion physics):
 * spatial-пружины с лёгким отскоком для перемещений и формы,
 * effects-пружины без отскока для цвета/прозрачности.
 */
internal object VeilMotion {
    val fastSpatial = spring<Float>(dampingRatio = 0.9f, stiffness = 1400f)
    val defaultSpatial = spring<Float>(dampingRatio = 0.9f, stiffness = 700f)
    val slowSpatial = spring<Float>(dampingRatio = 0.9f, stiffness = 300f)
    val fastEffects = spring<Float>(dampingRatio = 1f, stiffness = 3800f)
    val defaultEffects = spring<Float>(dampingRatio = 1f, stiffness = 1600f)
}

/** Пружинное вдавливание при нажатии — характерная физика Expressive. */
@Composable
internal fun pressScale(source: MutableInteractionSource, pressed: Float = 0.95f): Float {
    if (LocalTvMode.current || LocalReduceMotion.current) return 1f
    val isPressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressed else 1f,
        animationSpec = VeilMotion.fastSpatial,
        label = "press",
    )
    return scale
}

/**
 * Всплывание элемента снизу: лёгкий отскок с короткой задержкой (10 мс × индекс).
 * Жесткая пружина stiffness=750 → быстро, но с характерным «подпрыгиванием».
 */
@Composable
internal fun Modifier.staggerIn(seen: MutableSet<Any>, key: Any, index: Int): Modifier {
    if (LocalTvMode.current || LocalReduceMotion.current) return this
    val already = remember { key in seen }
    val progress = remember { Animatable(if (already) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!already) {
            delay(index.coerceIn(0, 6) * 10L)
            progress.animateTo(1f, VeilMotion.defaultSpatial)
            seen += key
        }
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * 16.dp.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s
        scaleY = s
    }
}
