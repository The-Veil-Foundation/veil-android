package dev.local.veil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Матрица QR: true — тёмный модуль. */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = bits[y * size + x]
}

/** Кодирует текст в QR; null — платформа не умеет или текст слишком длинный. */
expect fun encodeQr(text: String): QrMatrix?

/** QR на белой карточке с полями — тёмная тема не мешает сканированию. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, side: Dp = 240.dp) {
    val matrix = remember(text) { encodeQr(text) }
    Surface(color = Color.White, shape = RoundedCornerShape(28.dp), modifier = modifier) {
        Box(Modifier.size(side).padding(18.dp), contentAlignment = Alignment.Center) {
            if (matrix == null) {
                Text(
                    tr("Не удалось создать QR: данные слишком длинные", "Could not create the QR code: the data is too long"),
                    color = Color.Black,
                    textAlign = TextAlign.Center,
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val cell = this.size.minDimension / matrix.size
                    val radius = CornerRadius(cell * 0.2f)
                    for (y in 0 until matrix.size) {
                        for (x in 0 until matrix.size) {
                            if (matrix.isDark(x, y)) {
                                drawRoundRect(
                                    color = Color.Black,
                                    topLeft = Offset(x * cell, y * cell),
                                    size = Size(cell + 0.6f, cell + 0.6f),
                                    cornerRadius = radius,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
