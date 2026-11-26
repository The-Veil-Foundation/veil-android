package dev.local.veil.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal const val MAX_QR_IMAGE_BYTES = 16 * 1024 * 1024
internal const val MAX_QR_IMAGE_EDGE = 2048
internal class QrImageTooLarge : Exception()

internal fun readQrImageBytes(input: InputStream, limit: Int = MAX_QR_IMAGE_BYTES): ByteArray {
    require(limit > 0)
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return out.toByteArray()
        if (count > limit - out.size()) throw QrImageTooLarge()
        out.write(buffer, 0, count)
    }
}

internal fun qrImageSampleSize(width: Int, height: Int): Int {
    require(width > 0 && height > 0)
    if (width > 32768 || height > 32768) throw QrImageTooLarge()
    var sample = 1
    while ((width + sample - 1) / sample > MAX_QR_IMAGE_EDGE || (height + sample - 1) / sample > MAX_QR_IMAGE_EDGE) sample *= 2
    return sample
}

internal fun decodeQrPixels(width: Int, height: Int, pixels: IntArray): String? {
    require(width > 0 && height > 0 && width <= MAX_QR_IMAGE_EDGE && height <= MAX_QR_IMAGE_EDGE)
    require(pixels.size == width * height)
    val opaque = IntArray(pixels.size) { index ->
        val pixel = pixels[index]
        val alpha = pixel ushr 24
        fun channel(shift: Int): Int = (((pixel ushr shift) and 255) * alpha + 255 * (255 - alpha)) / 255
        (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
    val source = RGBLuminanceSource(width, height, opaque)
    val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
    )
    for (candidate in listOf(source, source.invert())) {
        val reader = MultiFormatReader()
        try {
            return reader.decode(BinaryBitmap(HybridBinarizer(candidate)), hints).text
        } catch (_: ReaderException) {
        } finally {
            reader.reset()
        }
    }
    return null
}
