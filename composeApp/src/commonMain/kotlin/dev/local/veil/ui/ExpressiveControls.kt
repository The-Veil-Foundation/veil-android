package dev.local.veil.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Large primary action with a spring-driven round-to-square press state. */
@Composable
internal fun ExpressiveActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    active: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduceMotion = LocalReduceMotion.current || LocalTvMode.current
    val corner by animateDpAsState(
        targetValue = when {
            pressed -> 16.dp
            active -> 24.dp
            else -> 36.dp
        },
        animationSpec = if (reduceMotion) snap() else spring(dampingRatio = .75f, stiffness = 700f),
        label = "action-shape",
    )
    val scale = pressScale(source, .97f)
    val shape = RoundedCornerShape(corner)
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        shape = shape,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp),
        modifier = modifier
            .heightIn(min = 64.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .tvFocusRing(shape),
        content = content,
    )
}
