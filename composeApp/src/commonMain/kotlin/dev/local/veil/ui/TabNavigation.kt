package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.local.veil.platform.PlatformBackHandler

/** Sibling pages move in tab order; the same transition can be scrubbed by touch or predictive back. */
@Composable
internal fun <T> TabNavigation(
    page: T,
    tabs: List<T>,
    pageOrder: (T) -> Int,
    previous: T?,
    onSelect: (T) -> Unit,
    swipeEnabled: Boolean,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val seek = remember { SeekableTransitionState(page) }
    var backProgress by remember { mutableStateOf(-1f) }
    var swipeTarget by remember { mutableStateOf<T?>(null) }
    var swipeProgress by remember { mutableStateOf(0f) }
    val reduced = !animationsEnabled || LocalReduceMotion.current || LocalTvMode.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val physicalForward = if (rtl) -1 else 1

    PlatformBackHandler(
        enabled = previous != null,
        onProgress = { backProgress = it },
        onBack = { previous?.let(onSelect) },
    )
    LaunchedEffect(page, previous, backProgress, swipeTarget, swipeProgress, reduced) {
        when {
            reduced -> seek.snapTo(page)
            backProgress >= 0f && previous != null -> seek.seekTo(backProgress, previous)
            swipeTarget != null -> seek.seekTo(swipeProgress, swipeTarget!!)
            else -> seek.animateTo(page, tween(240))
        }
    }

    val gestures = if (swipeEnabled && !LocalTvMode.current) {
        Modifier.pointerInput(page, tabs, rtl) {
            val edge = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Leave the system's edge-back gesture alone. Child sliders/pagers get first
                // refusal: awaitHorizontalTouchSlopOrCancellation stops when they consume it.
                if (down.position.x < edge || down.position.x > size.width - edge) return@awaitEachGesture
                val startIndex = tabs.indexOf(page)
                if (startIndex < 0) return@awaitEachGesture
                var distance = 0f
                var direction = 0
                var target: T? = null
                val velocity = VelocityTracker()
                velocity.addPosition(down.uptimeMillis, down.position)
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                    direction = if (overSlop * physicalForward < 0f) 1 else -1
                    target = tabs.getOrNull(startIndex + direction)
                    if (target != null) {
                        distance = overSlop
                        change.consume()
                    }
                } ?: return@awaitEachGesture
                swipeTarget = target
                fun updateProgress() {
                    swipeProgress = (-distance * physicalForward * direction / size.width.coerceAtLeast(1))
                        .coerceIn(0f, 1f)
                }
                updateProgress()
                try {
                    val completed = horizontalDrag(drag.id) { change ->
                        distance += change.positionChange().x
                        velocity.addPosition(change.uptimeMillis, change.position)
                        updateProgress()
                        change.consume()
                    }
                    val speed = -velocity.calculateVelocity().x * physicalForward * direction
                    if (completed && (swipeProgress >= .22f || (swipeProgress > .025f && speed > 700.dp.toPx()))) {
                        target?.let(onSelect)
                    }
                } finally {
                    swipeTarget = null
                    swipeProgress = 0f
                }
            }
        }
    } else Modifier

    val transition = rememberTransition(seek, label = "tab-navigation")
    transition.AnimatedContent(
        modifier = modifier.then(gestures),
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else {
                val direction = (if (pageOrder(targetState) > pageOrder(initialState)) 1 else -1) * physicalForward
                (slideInHorizontally(tween(240)) { it * direction } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(240)) { -it * direction } + fadeOut(tween(200)))
            }
        },
    ) { destination ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content(destination)
        }
    }
}
