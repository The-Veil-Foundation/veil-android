package dev.local.veil.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import kotlinx.coroutines.launch
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

val LocalTvMode = staticCompositionLocalOf { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvIndicationProvider(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val indication = remember(accent) { TvIndication(accent) }

    val ripple = remember(accent) {
        RippleConfiguration(
            color = accent,
            rippleAlpha = RippleAlpha(
                draggedAlpha = 0.16f,
                focusedAlpha = 0.22f,
                hoveredAlpha = 0.16f,
                pressedAlpha = 0.3f,
            ),
        )
    }
    CompositionLocalProvider(
        LocalIndication provides indication,
        LocalRippleConfiguration provides ripple,
        LocalTvMode provides true,
        content = content,
    )
}

private class TvIndication(private val accent: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        TvIndicationNode(interactionSource, accent)

    override fun equals(other: Any?): Boolean = other is TvIndication && other.accent == accent

    override fun hashCode(): Int = accent.hashCode()
}

private class TvIndicationNode(
    private val interactionSource: InteractionSource,
    private val accent: Color,
) : Modifier.Node(), DrawModifierNode {
    private var focused = false

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                val next = when (interaction) {
                    is FocusInteraction.Focus -> true
                    is FocusInteraction.Unfocus -> false
                    else -> return@collect
                }
                if (next != focused) {
                    focused = next
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        val radius = CornerRadius(16.dp.toPx())
        if (focused) drawRoundRect(color = accent.copy(alpha = 0.16f), cornerRadius = radius)
        drawContent()
        if (focused) {
            val inset = 4.dp.toPx()
            drawRoundRect(
                color = accent,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(32.dp.toPx()),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
    }
}

fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.onSurface
    val reduceMotion = LocalReduceMotion.current
    val scale by animateFloatAsState(
        if (focused && !reduceMotion) 1.03f else 1f,
        if (reduceMotion) androidx.compose.animation.core.snap() else tween(120),
        label = "tv-focus",
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) ring else Color.Transparent, shape)
}

fun Modifier.tvKeyScroll(scroll: androidx.compose.foundation.ScrollState, shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val step = with(LocalDensity.current) { 160.dp.toPx() }
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.onSurface
    this.onFocusChanged { focused = it.isFocused }
        .border(if (focused) 2.dp else 0.dp, if (focused) ring else Color.Transparent, shape)
        .onPreviewKeyEvent { event ->
            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (!focused) return@onPreviewKeyEvent false
            when (event.key) {
                androidx.compose.ui.input.key.Key.DirectionDown -> scroll.canScrollForward.also {
                    if (it) scope.launch { scroll.scrollBy(step) }
                }
                androidx.compose.ui.input.key.Key.DirectionUp -> scroll.canScrollBackward.also {
                    if (it) scope.launch { scroll.scrollBy(-step) }
                }
                else -> false
            }
        }
        .focusable()
}
