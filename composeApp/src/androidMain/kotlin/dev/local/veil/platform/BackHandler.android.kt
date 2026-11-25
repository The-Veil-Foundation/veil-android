package dev.local.veil.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.activity.compose.PredictiveBackHandler
import kotlinx.coroutines.flow.collect

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) {
    val progressCallback = rememberUpdatedState(onProgress)
    val backCallback = rememberUpdatedState(onBack)
    PredictiveBackHandler(enabled = enabled) { events ->
        try {
            events.collect { progressCallback.value(it.progress.coerceIn(0f, 1f)) }
            backCallback.value()
        } finally {
            progressCallback.value(-1f)
        }
    }
}
