package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.AddInput
import dev.local.veil.core.Pairing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Единый лист «Добавить», как AddServerSheet на macOS: пользователь вставляет
 * что есть, лист сам понимает, подписка это или узлы, и говорит об этом до
 * нажатия. Подписка скачивается, пока лист открыт: ошибка видна на месте, а
 * вставленный текст не пропадает.
 */
@Composable
internal fun AddSheet(
    state: VeilState,
    onDone: () -> Unit,
    onCancel: (() -> Unit)? = null,
    onScan: (() -> Unit)? = null,
    onReceive: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val tv = LocalTvMode.current
    val textFocus = remember { FocusRequester() }
    LaunchedEffect(tv) {
        if (tv) {
            delay(400)
            runCatching { textFocus.requestFocus() }
        }
    }

    val pairing = remember(text) { Pairing.parse(text) != null }
    val kind = remember(text) { if (pairing) AddInput.Kind.Empty else AddInput.classify(text) }
    val canAdd = !working && (pairing || kind is AddInput.Kind.Subscription || kind is AddInput.Kind.Servers)

    fun submit() {
        if (!canAdd) return
        error = null
        working = true
        scope.launch {
            val result = state.submitAdd(text, name)
            working = false
            if (result == null) onDone() else error = result
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                tr("Добавить", "Add"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
            )
            Text(
                tr("Вставьте ссылку на подписку, ссылки серверов или конфиг", "Paste a subscription link, server links or a config"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                error = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp, max = 220.dp)
                .focusRequester(textFocus),
            shape = RoundedCornerShape(20.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            placeholder = {
                Text(
                    "https://…/sub\nvless://…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            },
            colors = veilFieldColors(),
            trailingIcon = {
                if (text.isEmpty()) {
                    IconButton(onClick = {
                        clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { text = it.trim() }
                    }) {
                        AppIcon(AppIcons.Paste, contentDescription = tr("Вставить", "Paste"))
                    }
                } else {
                    IconButton(onClick = {
                        text = ""
                        name = ""
                        error = null
                    }) {
                        AppIcon(AppIcons.Close, contentDescription = tr("Очистить", "Clear"))
                    }
                }
            },
        )

        Detection(kind = kind, pairing = pairing)

        AnimatedVisibility(
            visible = kind is AddInput.Kind.Subscription,
            enter = fadeIn(tween(150)) + expandVertically(tween(180)),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(150)),
        ) {
            OutlinedTextField(
                colors = veilFieldColors(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                label = { Text(tr("Название (необязательно)", "Name (optional)")) },
                placeholder = { Text((kind as? AddInput.Kind.Subscription)?.host.orEmpty()) },
            )
        }

        AnimatedVisibility(visible = error != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(100))) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(AppIcons.Danger, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        Button(
            onClick = ::submit,
            enabled = canAdd,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (working) {
                ExpressiveLoader(diameter = 24.dp)
                Spacer(Modifier.size(10.dp))
                Text(tr("Загружаю подписку…", "Loading subscription…"), fontWeight = FontWeight.Bold)
            } else {
                Text(
                    when {
                        pairing -> tr("Отправить на приставку", "Send to the TV box")
                        kind is AddInput.Kind.Subscription -> tr("Добавить подписку", "Add subscription")
                        kind is AddInput.Kind.Servers && kind.body.nodes.size > 1 ->
                            tr("Добавить серверы: ${kind.body.nodes.size}", "Add ${kind.body.nodes.size} servers")
                        kind is AddInput.Kind.Servers -> tr("Добавить сервер", "Add server")
                        else -> tr("Добавить", "Add")
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        if (onScan != null || onReceive != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (onScan != null) {
                    SecondaryAction(AppIcons.Scan, tr("Сканировать QR", "Scan QR"), onScan, Modifier.weight(1f))
                }
                if (onReceive != null) {
                    SecondaryAction(AppIcons.Transfer, tr("С устройства", "From device"), onReceive, Modifier.weight(1f))
                }
            }
        }

        if (onCancel != null) {
            TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(tr("Отмена", "Cancel")) }
        }
    }
}

@Composable
private fun SecondaryAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier.height(52.dp),
    ) {
        AppIcon(icon, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Строка «что мы поняли из вставки» — видно до нажатия «Добавить». */
@Composable
private fun Detection(kind: AddInput.Kind, pairing: Boolean) {
    val scheme = MaterialTheme.colorScheme
    data class Line(val icon: ImageVector?, val text: String, val color: Color)
    val line = when {
        pairing -> Line(AppIcons.Qr, tr("QR приставки: отправим ей ваши подписки", "TV box QR: your subscriptions will be sent to it"), scheme.primary)
        kind is AddInput.Kind.Subscription -> Line(AppIcons.Cloud, tr("Подписка · ${kind.host}", "Subscription · ${kind.host}"), scheme.primary)
        kind is AddInput.Kind.Servers -> {
            val nodes = kind.body.nodes
            val label = if (nodes.size == 1) {
                tr("Сервер: ${nodes.first().name}", "Server: ${nodes.first().name}")
            } else {
                tr("Найдено серверов: ${nodes.size}", "Servers found: ${nodes.size}")
            }
            Line(AppIcons.Check, "$label · ${kind.body.format.label}", scheme.primary)
        }
        kind is AddInput.Kind.Invalid -> Line(AppIcons.Danger, tr("Не похоже на ссылку или адрес подписки", "Not a link or subscription URL"), scheme.error)
        else -> Line(null, "vless · vmess · trojan · ss · hy2 · tuic · wireguard · https", scheme.onSurfaceVariant)
    }
    AnimatedContent(
        targetState = line,
        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) },
        label = "detection",
    ) { current ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        ) {
            current.icon?.let { AppIcon(it, modifier = Modifier.size(18.dp), tint = current.color) }
            Text(
                current.text,
                style = MaterialTheme.typography.labelLarge,
                color = current.color,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
