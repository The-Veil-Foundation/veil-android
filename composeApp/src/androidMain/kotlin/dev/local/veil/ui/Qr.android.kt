package dev.local.veil.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

actual fun encodeQr(text: String): QrMatrix? = runCatching {
    val hints = mapOf(
        EncodeHintType.MARGIN to 0,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    // Размер 0×0: матрица ровно по числу модулей, поля рисует карточка.
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    QrMatrix(matrix.width, BooleanArray(matrix.width * matrix.height) { matrix.get(it % matrix.width, it / matrix.width) })
}.getOrNull()
