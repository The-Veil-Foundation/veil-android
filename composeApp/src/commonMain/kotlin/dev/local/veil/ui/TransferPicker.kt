package dev.local.veil.ui

import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * «Что отправить на приставку?» — после скана QR или «Открыть в Veil» на
 * странице приставки. По умолчанию отмечено всё; ключи уходят только после
 * «Отправить».
 */
@Composable
internal fun TransferPicker(state: VeilState) {
    val subscriptions = state.subscriptions
    val manualCount = state.servers.count { it.source == null }
    // Храним снятые галочки, а не отмеченные: при холодном старте по ссылке
    // список подписок ещё грузится, и «всё отмечено» должно включать то,
    // что появится через мгновение.
    var excluded by remember { mutableStateOf(emptySet<String>()) }
    var manualOff by remember { mutableStateOf(false) }
    val chosen = subscriptions.map { it.url }.filterNot { it in excluded }.toSet()
    val manual = manualCount > 0 && !manualOff
    val nothing = chosen.isEmpty() && !manual

    AlertDialog(
        onDismissRequest = state::dismissTransfer,
        shape = RoundedCornerShape(32.dp),
        icon = { AppIcon(AppIcons.Qr) },
        title = { Text(tr("Отправить на приставку", "Send to the TV box"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    tr(
                        "Отметьте, что перенести. Данные уйдут напрямую на устройство в вашей сети.",
                        "Choose what to transfer. It goes straight to the device on your network.",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (subscriptions.isEmpty() && manualCount == 0) {
                    Text(
                        tr("Здесь пока нечего отправлять: добавьте подписку или сервер.", "Nothing to send yet: add a subscription or a server."),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    subscriptions.forEach { sub ->
                        val title = sub.title.ifBlank { sub.url.substringAfter("://").substringBefore('/') }
                        PickRow(
                            title = title,
                            subtitle = tr("Подписка · серверов: ${sub.nodeCount}", "Subscription · ${sub.nodeCount} servers"),
                            checked = sub.url in chosen,
                            onToggle = { excluded = if (sub.url in excluded) excluded - sub.url else excluded + sub.url },
                        )
                    }
                    if (manualCount > 0) {
                        PickRow(
                            title = tr("Добавленные вручную", "Added by hand"),
                            subtitle = tr("Серверов: $manualCount", if (manualCount == 1) "1 server" else "$manualCount servers"),
                            checked = manual,
                            onToggle = { manualOff = !manualOff },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !nothing,
                modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                onClick = { state.confirmTransfer(chosen, manual) },
            ) { Text(tr("Отправить", "Send"), fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(
                modifier = Modifier.tvFocusRing(RoundedCornerShape(50)),
                onClick = state::dismissTransfer,
            ) { Text(tr("Отмена", "Cancel")) }
        },
    )
}

@Composable
private fun PickRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Окно переноса: пока идёт отправка или загрузка, его нельзя смахнуть —
 * полоска сверху незаметна, и человек закрывал приложение на полпути.
 * В конце — итог и кнопка «Готово».
 */
@Composable
internal fun TransferProgressDialog(state: VeilState) {
    val progress = state.transferProgress ?: return
    val finished = progress as? VeilState.TransferProgress.Finished
    androidx.compose.ui.window.Dialog(
        onDismissRequest = state::closeTransferProgress,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = finished != null,
            dismissOnClickOutside = finished != null,
        ),
    ) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(32.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (finished == null) {
                    ExpressiveLoader(diameter = 56.dp)
                } else {
                    androidx.compose.foundation.layout.Box(
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(
                                if (finished.ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppIcon(
                            if (finished.ok) AppIcons.Check else AppIcons.Danger,
                            tint = if (finished.ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
                val title = when (progress) {
                    is VeilState.TransferProgress.Sending -> tr("Передаём на приставку…", "Sending to the TV box…")
                    is VeilState.TransferProgress.Receiving -> tr("Получаем подписки…", "Receiving subscriptions…")
                    is VeilState.TransferProgress.Finished -> progress.title
                }
                val body = when (progress) {
                    is VeilState.TransferProgress.Sending -> tr(
                        "Подписок: ${progress.subscriptions}, серверов: ${progress.servers}. Не закрывайте приложение.",
                        "Subscriptions: ${progress.subscriptions}, servers: ${progress.servers}. Keep the app open.",
                    )
                    is VeilState.TransferProgress.Receiving -> if (progress.total > 0) {
                        tr("Загружено ${progress.done} из ${progress.total}. Не закрывайте приложение.", "Loaded ${progress.done} of ${progress.total}. Keep the app open.")
                    } else {
                        tr("Добавляем серверы. Не закрывайте приложение.", "Adding servers. Keep the app open.")
                    }
                    is VeilState.TransferProgress.Finished -> progress.message
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text(
                    body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                if (progress is VeilState.TransferProgress.Receiving && progress.total > 0) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { progress.done.toFloat() / progress.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (finished != null) {
                    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                    androidx.compose.material3.Button(
                        onClick = state::closeTransferProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .tvFocusRing(RoundedCornerShape(50))
                            .focusRequester(focus),
                    ) { Text(tr("Готово", "Done"), fontWeight = FontWeight.Bold) }
                    androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
            }
        }
    }
}
