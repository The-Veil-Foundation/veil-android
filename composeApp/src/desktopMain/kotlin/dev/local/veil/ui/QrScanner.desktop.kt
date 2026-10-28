package dev.local.veil.ui

import androidx.compose.runtime.Composable

actual val qrSupported: Boolean = false

@Composable
actual fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit) = Unit
