package dev.local.veil.platform

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onProgress: (Float) -> Unit, onBack: () -> Unit) = Unit
