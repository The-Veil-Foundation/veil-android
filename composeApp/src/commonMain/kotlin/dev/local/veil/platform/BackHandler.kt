package dev.local.veil.platform

import androidx.compose.runtime.Composable

@Composable
expect fun PlatformBackHandler(enabled: Boolean, onProgress: (Float) -> Unit = {}, onBack: () -> Unit)
