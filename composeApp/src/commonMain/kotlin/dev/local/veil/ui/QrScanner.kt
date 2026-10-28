package dev.local.veil.ui

import androidx.compose.runtime.Composable

/** Есть ли на платформе камера под QR-сканер. */
expect val qrSupported: Boolean

/**
 * Полноэкранный сканер QR. Возвращает первое распознанное содержимое:
 * это может быть ссылка на подписку или share-ссылка узла.
 */
@Composable
expect fun QrScannerDialog(onResult: (String) -> Unit, onDismiss: () -> Unit)
