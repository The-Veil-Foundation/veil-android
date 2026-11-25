package dev.local.veil.ui

import androidx.compose.runtime.Composable

internal sealed interface QrImageResult {
    data class Decoded(val text: String) : QrImageResult
    data object Cancelled : QrImageResult
    data object NotFound : QrImageResult
    data object TooLarge : QrImageResult
    data object Unreadable : QrImageResult
}

@Composable
internal expect fun rememberQrImagePicker(onResult: (QrImageResult) -> Unit): (() -> Unit)?
