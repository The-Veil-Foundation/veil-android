package dev.local.veil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Приём подписок с телефона: QR с адресом приставки в локальной сети.
 * На телевизоре камеры нет, поэтому код показывает приставка, а сканирует телефон.
 */
@Composable
fun ReceiveScreen(state: VeilState, modifier: Modifier = Modifier) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    val seen = remember { mutableSetOf<Any>() }

    DisposableEffect(Unit) {
        val session = state.startReceiving()
        url = session?.url
        failed = session == null
        onDispose { session?.stop() }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > 680.dp
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        val qrBlock: @Composable () -> Unit = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.staggerIn(seen, "qr", 0),
            ) {
                val address = url
                when {
                    failed -> Text(
                        tr("Нет подключения к локальной сети. Подключитесь к Wi-Fi или Ethernet и откройте экран снова.", "No local network connection. Connect to Wi-Fi or Ethernet and open this screen again."),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                    )
                    address == null -> ExpressiveLoader(diameter = 56.dp)
                    else -> {
                        // На приставке код должен читаться с дивана: размер
                        // считаем от экрана, а не фиксируем в 320 dp.
                        val qrSide = if (wide) {
                            minOf(availableWidth * 0.44f, availableHeight * 0.70f, 600.dp)
                        } else {
                            minOf(availableWidth - 56.dp, 320.dp)
                        }
                        QrCode(text = address, side = qrSide)
                        Text(
                            address,
                            style = MaterialTheme.typography.labelMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        val infoBlock: @Composable (Modifier) -> Unit = { blockModifier ->
            Column(blockModifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    tr("Перенос с телефона", "Transfer from phone"),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    modifier = Modifier.staggerIn(seen, "title", 1),
                )
                val steps = listOf(
                    tr("Откройте Veil на телефоне.", "Open Veil on your phone."),
                    tr("Нажмите «+», затем «Сканировать QR-код».", "Tap “+” then “Scan QR code”."),
                    tr("Наведите камеру на этот код и подтвердите отправку.", "Point the camera at this code and confirm the transfer."),
                )
                steps.forEachIndexed { index, step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.staggerIn(seen, "step-$index", index + 2),
                    ) {
                        CookieBadge(
                            diameter = 38.dp,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            spinning = false,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    }
                }
                Text(
                    tr("Нет Veil на телефоне? Откройте адрес под кодом камерой: в браузере появится страница, ", "No Veil on your phone? Scan the address below the code with your camera: a page will open in the browser ") +
                        tr("куда можно вставить ссылку на подписку.", "where you can paste a subscription link."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.staggerIn(seen, "alt", 5),
                )
                Text(
                    tr("Передача идёт только внутри вашей сети Wi-Fi, адрес действует, пока открыт этот экран.", "Transfer stays inside your Wi-Fi network; the address is valid while this screen is open."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.staggerIn(seen, "note", 6),
                )
                if (url != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ExpressiveLoader(diameter = 22.dp)
                        Text(tr("Ждём телефон…", "Waiting for phone…"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (wide) {
            Row(
                Modifier.fillMaxSize().padding(40.dp),
                horizontalArrangement = Arrangement.spacedBy(56.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                qrBlock()
                infoBlock(Modifier.weight(1f))
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                qrBlock()
                infoBlock(Modifier.fillMaxWidth())
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}
