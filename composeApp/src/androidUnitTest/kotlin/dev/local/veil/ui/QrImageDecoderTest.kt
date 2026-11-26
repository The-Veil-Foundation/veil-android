package dev.local.veil.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class QrImageDecoderTest {
    private fun pixels(text: String, inverted: Boolean = false, transparent: Boolean = false): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"))
        return IntArray(320 * 320) { index ->
            val dark = matrix[index % 320, index / 320] != inverted
            if (dark) 0xff000000.toInt() else if (transparent) 0 else 0xffffffff.toInt()
        }
    }

    @Test
    fun decodesNormalInvertedAndTransparentQr() {
        val link = "https://example.org/subscription/demo"
        assertEquals(link, decodeQrPixels(320, 320, pixels(link)))
        assertEquals(link, decodeQrPixels(320, 320, pixels(link, inverted = true)))
        assertEquals(link, decodeQrPixels(320, 320, pixels(link, transparent = true)))
    }

    @Test
    fun preservesUtf8AndDoesNotAcceptEmptyImages() {
        val text = "Veil · Тест 日本語"
        assertEquals(text, decodeQrPixels(320, 320, pixels(text)))
        assertNull(decodeQrPixels(64, 64, IntArray(64 * 64) { -1 }))
    }

    @Test
    fun enforcesFileAndPixelLimits() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertContentEquals(bytes, readQrImageBytes(ByteArrayInputStream(bytes), 4))
        assertFailsWith<QrImageTooLarge> { readQrImageBytes(ByteArrayInputStream(bytes), 3) }
        assertFailsWith<IllegalArgumentException> { qrImageSampleSize(0, 100) }
        assertFailsWith<QrImageTooLarge> { qrImageSampleSize(100000, 100) }
        assertEquals(1, qrImageSampleSize(2048, 2048))
        assertEquals(4, qrImageSampleSize(8000, 6000))
        assertEquals(4, qrImageSampleSize(4097, 3000))
        assertFailsWith<IllegalArgumentException> { decodeQrPixels(3000, 1, IntArray(3000)) }
        assertFailsWith<IllegalArgumentException> { decodeQrPixels(64, 64, IntArray(1)) }
    }
}
