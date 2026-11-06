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
import androidx.compose.foundation.gestures.animateScrollBy
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

/** true, когда приложение запущено на телевизоре: меняет навигацию и фокус. */
val LocalTvMode = staticCompositionLocalOf { false }

/**
 * Яркая рамка вокруг элемента, получившего фокус с пульта. Обычная ripple
 * на телевизоре почти не видна с дивана, поэтому фокус обводим акцентом.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvIndicationProvider(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val indication = remember(accent) { TvIndication(accent) }
    // Кнопки, плитки и пункты навигации Material рисуют свою ripple, а не
    // LocalIndication, и фокус у неё — едва заметная дымка. С дивана не
    // видно, где стоит пульт, поэтому на телевизоре фокус заливается ярко.
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

/**
 * Подсветка фокуса через [IndicationNodeFactory]: новый clickable принимает
 * только такую, со старым Indication приложение на телевизоре падало при
 * первом же кликабельном элементе.
 */
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
            drawRoundRect(color = accent, cornerRadius = radius, style = Stroke(width = 2.5.dp.toPx()))
        }
    }
}

/**
 * Обводка фокуса для компонентов Material (кнопки, плитки, пункты навигации):
 * они рисуют свою ripple и не видят [LocalIndication]. На телефоне ничего не
 * делает. Ставится до кликабельного модификатора, чтобы видеть его фокус.
 */
fun Modifier.tvFocusRing(shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.onSurface
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, tween(120), label = "tv-focus")
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 3.dp else 0.dp, if (focused) ring else Color.Transparent, shape)
}

/**
 * Lets the remote scroll a block that has nothing focusable inside, such as a
 * list of details: the block itself takes focus, and up/down scroll it until
 * its edge, then hand the key on so focus can leave.
 */
fun Modifier.tvKeyScroll(scroll: androidx.compose.foundation.ScrollState, shape: Shape = RoundedCornerShape(16.dp)): Modifier = composed {
    if (!LocalTvMode.current) return@composed this
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    val ring = MaterialTheme.colorScheme.onSurface
    this.onFocusChanged { focused = it.hasFocus }
        .border(if (focused) 2.dp else 0.dp, if (focused) ring else Color.Transparent, shape)
        .onPreviewKeyEvent { event ->
            if (event.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val step = 160f
            when (event.key) {
                androidx.compose.ui.input.key.Key.DirectionDown -> scroll.canScrollForward.also {
                    if (it) scope.launch { scroll.animateScrollBy(step) }
                }
                androidx.compose.ui.input.key.Key.DirectionUp -> scroll.canScrollBackward.also {
                    if (it) scope.launch { scroll.animateScrollBy(-step) }
                }
                else -> false
            }
        }
        .focusable()
}
