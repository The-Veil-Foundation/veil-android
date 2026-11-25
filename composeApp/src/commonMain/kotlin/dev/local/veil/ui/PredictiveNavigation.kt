package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import dev.local.veil.platform.PlatformBackHandler

@Composable
internal fun <T> PredictiveNavigation(
    page: T,
    previous: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val seek = remember { SeekableTransitionState(page) }
    var progress by remember { mutableStateOf(-1f) }
    val reduced = LocalReduceMotion.current || LocalTvMode.current
    PlatformBackHandler(enabled = previous != null, onProgress = { progress = it }, onBack = onBack)
    LaunchedEffect(page, previous, progress, reduced) {
        if (progress >= 0f && previous != null && !reduced) {
            seek.seekTo(progress, previous)
        } else if (reduced) {
            seek.snapTo(page)
        } else {
            seek.animateTo(page, tween(220))
        }
    }
    val transition = rememberTransition(seek, label = "predictive-navigation")
    transition.AnimatedContent(
        modifier = modifier,
        transitionSpec = {
            if (reduced) EnterTransition.None togetherWith ExitTransition.None
            else if (targetState == previous) {
                (slideInHorizontally(tween(220)) { -it / 4 } + fadeIn(tween(220)) + scaleIn(tween(220), initialScale = .94f)) togetherWith
                    (slideOutHorizontally(tween(220)) { it } + fadeOut(tween(220)))
            } else {
                (slideInHorizontally(tween(220)) { it } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 4 } + fadeOut(tween(220)) + scaleOut(tween(220), targetScale = .94f))
            }
        },
    ) { destination ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content(destination)
        }
    }
}
