package dev.local.veil.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal actual fun rememberQrImagePicker(onResult: (QrImageResult) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val pickerAvailable = remember(context) {
        android.content.Intent(android.content.Intent.ACTION_GET_CONTENT)
            .setType("image/*")
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .resolveActivity(context.packageManager) != null
    }
    val scope = rememberCoroutineScope()
    val callback = rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            callback.value(QrImageResult.Cancelled)
        } else scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { readQrImageBytes(it) }
                    if (bytes == null) QrImageResult.Unreadable else decodeImage(bytes)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: QrImageTooLarge) {
                QrImageResult.TooLarge
            } catch (_: Exception) {
                QrImageResult.Unreadable
            }
            callback.value(result)
        }
    }
    if (!pickerAvailable) return null
    return {
        try {
            launcher.launch("image/*")
        } catch (_: android.content.ActivityNotFoundException) {
            callback.value(QrImageResult.Unreadable)
        }
    }
}

private fun decodeImage(bytes: ByteArray): QrImageResult {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return QrImageResult.Unreadable
    val options = BitmapFactory.Options().apply {
        inSampleSize = qrImageSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return QrImageResult.Unreadable
    return try {
        if (bitmap.width > MAX_QR_IMAGE_EDGE || bitmap.height > MAX_QR_IMAGE_EDGE) throw QrImageTooLarge()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        decodeQrPixels(bitmap.width, bitmap.height, pixels)?.let { QrImageResult.Decoded(it) } ?: QrImageResult.NotFound
    } finally {
        bitmap.recycle()
    }
}
