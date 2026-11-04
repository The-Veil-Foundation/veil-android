@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package dev.local.veil.ui

import androidx.compose.foundation.Indication
import androidx.compose.foundation.IndicationInstance
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** true, когда приложение запущено на телевизоре: меняет навигацию и фокус. */
val LocalTvMode = staticCompositionLocalOf { false }

/**
 * Яркая рамка вокруг элемента, получившего фокус с пульта. Обычная ripple
 * на телевизоре почти не видна с дивана, поэтому фокус обводим акцентом.
 */
@Composable
fun TvIndicationProvider(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val accent = MaterialTheme.colorScheme.primary
    val indication = remember(accent) { TvIndication(accent) }
    CompositionLocalProvider(
        LocalIndication provides indication,
        LocalTvMode provides true,
        content = content,
    )
}

private class TvIndication(private val accent: Color) : Indication {
    @Composable
    override fun rememberUpdatedInstance(interactionSource: InteractionSource): IndicationInstance {
        val focused by interactionSource.interactions
            .map { it is FocusInteraction.Focus }
            .distinctUntilChanged()
            .collectAsState(initial = false)
        return remember(focused, accent) { TvIndicationInstance(focused, accent) }
    }
}

private class TvIndicationInstance(
    private val focused: Boolean,
    private val accent: Color,
) : IndicationInstance {
    override fun ContentDrawScope.drawIndication() {
        val radius = CornerRadius(16.dp.toPx())
        if (focused) {
            drawRoundRect(color = accent.copy(alpha = 0.16f), cornerRadius = radius)
        }
        drawContent()
        if (focused) {
            drawRoundRect(
                color = accent,
                cornerRadius = radius,
                style = Stroke(width = 2.5.dp.toPx()),
            )
        }
    }
}
