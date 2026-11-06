package dev.local.veil.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Матрица QR: true — тёмный модуль. */
class QrMatrix(val size: Int, private val bits: BooleanArray) {
    fun isDark(x: Int, y: Int): Boolean = bits[y * size + x]
}

/** Кодирует текст в QR; null — платформа не умеет или текст слишком длинный. */
expect fun encodeQr(text: String): QrMatrix?

/**
 * QR на белой карточке. Модули рисуются квадратами, выровненными по целым
 * пикселям, с полноценным «тихим полем» в 4 модуля — иначе на приставке код
 * получается «мыльным» и камера телефона его не берёт.
 */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier, side: Dp = 240.dp) {
    val matrix = remember(text) { encodeQr(text) }
    Surface(
        color = Color.White,
        contentColor = Color.Black,
        shape = RoundedCornerShape(24.dp),
        modifier = modifier,
    ) {
        Box(Modifier.size(side).padding(14.dp), contentAlignment = Alignment.Center) {
            if (matrix == null) {
                Text(
                    tr("Не удалось создать QR: данные слишком длинные", "Could not create the QR code: the data is too long"),
                    color = Color.Black,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val quiet = 4
                    val modules = matrix.size + quiet * 2
                    val limit = size.minDimension
                    // Целый размер модуля + целочисленное выравнивание: без
                    // полупрозрачных краёв и «дрожащих» границ при масштабировании.
                    val rawCell = limit / modules
                    val cell = if (rawCell >= 1f) floor(rawCell) else max(rawCell, 0.5f)
                    val total = cell * modules
                    val left = ((size.width - total) / 2f).roundToInt().toFloat()
                    val top = ((size.height - total) / 2f).roundToInt().toFloat()
                    val moduleSize = Size(cell + if (cell >= 2f) 0.35f else 0f, cell + if (cell >= 2f) 0.35f else 0f)

                    for (y in 0 until matrix.size) {
                        for (x in 0 until matrix.size) {
                            if (!matrix.isDark(x, y)) continue
                            drawRect(
                                color = Color.Black,
                                topLeft = Offset(
                                    left + (x + quiet) * cell,
                                    top + (y + quiet) * cell,
                                ),
                                size = moduleSize,
                            )
                        }
                    }
                }
            }
        }
    }
}
