package dev.local.veil.ui

import androidx.compose.runtime.Composable

expect val qrSupported: Boolean

@Composable
expect fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit)
