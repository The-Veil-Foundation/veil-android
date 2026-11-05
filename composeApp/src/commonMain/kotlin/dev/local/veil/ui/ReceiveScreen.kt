package dev.local.veil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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

    DisposableEffect(Unit) {
        val session = state.startReceiving()
        url = session?.url
        failed = session == null
        onDispose { session?.stop() }
    }

    val clipboard = LocalClipboardManager.current

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth > 680.dp
        val availableWidth = maxWidth
        val availableHeight = maxHeight

        val qrBlock: @Composable () -> Unit = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val address = url
                when {
                    failed -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(vertical = 24.dp),
                    ) {
                        Box(
                            Modifier.size(88.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            AppIcon(AppIcons.Wifi, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Text(
                            tr("Нет локальной сети", "No local network"),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            tr("Подключитесь к Wi-Fi или Ethernet и откройте экран снова.", "Connect to Wi-Fi or Ethernet and open this screen again."),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    address == null -> Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        ExpressiveLoader(diameter = 56.dp)
                    }
                    else -> {
                        // На приставке код должен читаться с дивана: размер
                        // считаем от экрана, а не фиксируем.
                        val qrSide = if (wide) {
                            minOf(availableWidth * 0.44f, availableHeight * 0.70f, 600.dp)
                        } else {
                            minOf(availableWidth - 96.dp, 300.dp)
                        }
                        QrCode(text = address, side = qrSide)
                        Surface(
                            onClick = {
                                clipboard.setText(AnnotatedString(address))
                                state.showBulletin(tr("Адрес скопирован", "Address copied"), BulletinKind.SUCCESS)
                            },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    address.substringAfter("://").substringBefore("/pair"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AppIcon(AppIcons.Copy, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Row(
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                ExpressiveLoader(diameter = 20.dp)
                                Text(
                                    if (state.busy) tr("Получаем…", "Receiving…") else tr("Ждём другое устройство", "Waiting for the other device"),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        val infoBlock: @Composable (Modifier) -> Unit = { blockModifier ->
            Column(blockModifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (wide) {
                    Text(
                        tr("Перенос с телефона", "Transfer from phone"),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(
                            tr("Как отправить", "How to send"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        val steps = listOf(
                            tr("Откройте Veil на другом устройстве.", "Open Veil on the other device."),
                            tr("Нажмите «+», затем «Сканировать QR».", "Tap “+”, then “Scan QR”."),
                            tr("Наведите камеру на код и подтвердите отправку.", "Point the camera at the code and confirm."),
                        )
                        steps.forEachIndexed { index, step ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Box(
                                    Modifier.size(32.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center,
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
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(horizontal = 8.dp),
                ) {
                    AppIcon(AppIcons.Wifi, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        tr("Передача идёт только внутри вашей сети, адрес действует, пока открыт этот экран. Нет Veil на втором устройстве? Отсканируйте код камерой — откроется страница, куда можно вставить ссылку.", "Transfer stays inside your network; the address works while this screen is open. No Veil on the other device? Scan the code with the camera — a page opens where you can paste a link."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
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
