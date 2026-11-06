package dev.local.veil.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Обёртка «потянуть, чтобы обновить». На телевизоре жестов нет, поэтому
 * pull-to-refresh отключается и остаётся обычный контейнер: обновление
 * вызывается кнопкой или автоматически.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableBox(
    enabled: Boolean,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    if (enabled) {
        val state = rememberPullToRefreshState()
        PullToRefreshBox(
            state = state,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = modifier,
            indicator = {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .size(width = 48.dp, height = 160.dp)
                        .clipToBounds(),
                ) {
                    Surface(
                        modifier = Modifier.size(48.dp).graphicsLayer {
                            translationY = state.distanceFraction * PullToRefreshDefaults.PositionalThreshold.toPx() - size.height
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isRefreshing) {
                                ExpressiveLoader(diameter = 36.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            } else {
                                PullLoadingShape(progress = { state.distanceFraction })
                            }
                        }
                    }
                }
            },
            content = content,
        )
    } else {
        Box(modifier = modifier, content = content)
    }
}
