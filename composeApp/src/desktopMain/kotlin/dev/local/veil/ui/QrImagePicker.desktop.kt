package dev.local.veil.ui

import androidx.compose.runtime.Composable

@Composable
internal actual fun rememberQrImagePicker(onResult: (QrImageResult) -> Unit): (() -> Unit)? = null
