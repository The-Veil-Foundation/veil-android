package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.ProxyConfig
import dev.local.veil.platform.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/* ------------------------------------------------------------------ */
/* Каркас листа: шапка, список действий, страницы «Посмотреть» и «QR»  */
/* ------------------------------------------------------------------ */

internal enum class SheetPage { ACTIONS, DETAILS, QR }

internal class SheetAction(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val destructive: Boolean = false,
    /** Если задан — действие сначала просит подтверждения этим текстом. */
    val confirmText: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
internal class SheetController(
    private val scope: CoroutineScope,
    private val sheetState: SheetState,
    private val dismiss: () -> Unit,
    val navigate: (SheetPage) -> Unit,
) {
    /** Выполняет [after] сразу и плавно убирает лист вниз. */
    fun close(after: () -> Unit = {}) {
        after()
        scope.launch {
            sheetState.hide()
            dismiss()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsSheet(
    icon: ImageVector,
    title: String,
    subtitle: String,
    qrText: String?,
    qrCaption: String,
    onShareQr: (() -> Unit)?,
    details: (@Composable (reveal: Boolean) -> Unit)?,
    buildActions: (SheetController) -> List<SheetAction>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(SheetPage.ACTIONS) }
    var reveal by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<SheetAction?>(null) }
    val seen = remember { mutableSetOf<Any>() }
    val controller = remember { SheetController(scope, sheetState, onDismiss) { page = it } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetHeader(icon = icon, title = title, subtitle = subtitle)

            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        (slideInHorizontally(tween(180)) { it / 3 } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(180)) { -it / 3 } + fadeOut(tween(180)))
                    } else {
                        (slideInHorizontally(tween(180)) { -it / 3 } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(180)))
                    }
                },
                label = "sheet-page",
            ) { current ->
                when (current) {
                    SheetPage.ACTIONS -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val actions = remember(onDismiss) { buildActions(controller) }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            actions.forEachIndexed { index, action ->
                                ActionTile(
                                    action = action,
                                    index = index,
                                    last = actions.lastIndex,
                                    seen = seen,
                                    onClick = {
                                        if (action.confirmText != null) confirming = action else action.onClick()
                                    },
                                )
                            }
                        }
                        AnimatedVisibility(
                            visible = confirming != null,
                            enter = expandVertically(tween(180)) + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            val pending = confirming
                            Surface(
                                shape = RoundedCornerShape(24.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            ) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        pending?.confirmText.orEmpty(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { pending?.onClick(); confirming = null },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError,
                                            ),
                                            shape = RoundedCornerShape(18.dp),
                                        ) { Text("Удалить") }
                                        TextButton(onClick = { confirming = null }) { Text("Отмена") }
                                    }
                                }
                            }
                        }
                    }

                    SheetPage.DETAILS -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PageBar(title = "Подробности", onBack = { page = SheetPage.ACTIONS }) {
                            TextButton(onClick = { reveal = !reveal }) {
                                Icon(
                                    if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(if (reveal) "Скрыть ключи" else "Показать ключи")
                            }
                        }
                        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                            details?.invoke(reveal)
                        }
                    }

                    SheetPage.QR -> Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PageBar(title = "QR-код", onBack = { page = SheetPage.ACTIONS }) {}
                        if (qrText != null) QrCode(text = qrText, side = 260.dp)
                        Text(
                            qrCaption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                        if (onShareQr != null) {
                            TextButton(onClick = onShareQr) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Поделиться ссылкой")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(icon: ImageVector, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CookieBadge(diameter = 56.dp, color = MaterialTheme.colorScheme.primaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PageBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        actions()
    }
}

/** Плитка действия: сегментированный список, пружинное нажатие, каскадное появление. */
@Composable
private fun ActionTile(
    action: SheetAction,
    index: Int,
    last: Int,
    seen: MutableSet<Any>,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val scale = pressScale(source, 0.97f)
    val top = if (index == 0) 24.dp else 6.dp
    val bottom = if (index == last) 24.dp else 6.dp
    val container = if (action.destructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (action.destructive) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        enabled = action.enabled,
        interactionSource = source,
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = container,
        contentColor = content,
        modifier = Modifier
            .fillMaxWidth()
            .staggerIn(seen, "tile-$index", index)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(action.icon, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(action.title, style = MaterialTheme.typography.titleMedium)
                if (action.subtitle != null) {
                    Text(
                        action.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = content.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Лист сервера                                                        */
/* ------------------------------------------------------------------ */

@Composable
fun ServerActionsSheet(
    state: VeilState,
    status: VpnStatus,
    server: ProxyConfig,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val link = remember(server) { state.linkFor(server) }
    val selected = state.selected?.id == server.id
    val ping = state.pings[server.id]
    val endpoint = if (server.balanced > 1) {
        "авто из ${server.balanced} транспортов"
    } else {
        server.endpoint
    }

    ActionsSheet(
        icon = Icons.Rounded.Dns,
        title = server.name,
        subtitle = buildString {
            append(server.proto.wire.uppercase()).append(" · ").append(endpoint)
            if (ping != null) append(" · ").append(ping).append(" мс")
        },
        qrText = link,
        qrCaption = "Ссылка содержит ключи доступа — показывайте её только своим устройствам.",
        onShareQr = link?.let { { state.share(it) } },
        details = { reveal -> ServerDetails(server, reveal) },
        buildActions = { sheet ->
            buildList {
                if (!selected) {
                    add(SheetAction(Icons.Rounded.CheckCircle, "Выбрать") {
                        sheet.close { state.select(server.id, status) }
                    })
                }
                add(SheetAction(Icons.Rounded.Speed, "Проверить пинг", "Метод: ${state.pingMode.label}") {
                    sheet.close { state.pingOne(server) }
                })
                add(SheetAction(Icons.Rounded.Visibility, "Посмотреть", "Параметры узла") {
                    sheet.navigate(SheetPage.DETAILS)
                })
                if (link != null) {
                    add(SheetAction(Icons.Rounded.QrCode2, "QR-код", "Перенести на другое устройство") {
                        sheet.navigate(SheetPage.QR)
                    })
                    add(SheetAction(Icons.Rounded.ContentCopy, "Копировать ссылку") {
                        sheet.close {
                            clipboard.setText(AnnotatedString(link))
                            state.showBulletin("Ссылка скопирована", BulletinKind.SUCCESS)
                        }
                    })
                    add(SheetAction(Icons.Rounded.Share, "Поделиться") {
                        sheet.close { state.share(link) }
                    })
                }
                add(
                    SheetAction(
                        icon = Icons.Rounded.Delete,
                        title = "Удалить",
                        destructive = true,
                        confirmText = "Удалить «${server.name}»? Это нельзя отменить.",
                    ) { sheet.close { state.removeServer(server.id) } },
                )
            }
        },
        onDismiss = onDismiss,
    )
}

/** Параметры узла. Ключи скрыты, пока их явно не покажут. */
@Composable
private fun ServerDetails(server: ProxyConfig, reveal: Boolean) {
    val rows = buildList {
        add(Triple("Протокол", server.proto.wire.uppercase(), false))
        add(Triple("Адрес", server.endpoint, false))
        if (server.balanced > 1) add(Triple("Транспортов", "${server.balanced} (балансировщик)", false))
        add(Triple("Транспорт", server.network.wire, false))
        add(Triple("Защита", server.security.wire, false))
        server.sni?.let { add(Triple("SNI", it, false)) }
        server.fingerprint?.let { add(Triple("Fingerprint", it, false)) }
        server.alpn?.takeIf { it.isNotEmpty() }?.let { add(Triple("ALPN", it.joinToString(", "), false)) }
        server.flow?.let { add(Triple("Flow", it, false)) }
        server.path?.let { add(Triple("Path", it, false)) }
        server.host?.let { add(Triple("Host", it, false)) }
        server.serviceName?.let { add(Triple("Service name", it, false)) }
        server.xhttpMode?.let { add(Triple("XHTTP mode", it, false)) }
        server.publicKey?.let { add(Triple("Public key", it, false)) }
        server.shortId?.let { add(Triple("Short ID", it, false)) }
        server.uuid?.let { add(Triple("UUID", it, true)) }
        server.password?.let { add(Triple("Пароль", it, true)) }
        server.privateKey?.let { add(Triple("Приватный ключ", it, true)) }
        server.presharedKey?.let { add(Triple("Preshared key", it, true)) }
        server.source?.let { add(Triple("Подписка", it.substringAfter("://").substringBefore('/'), false)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, (label, value, secret) ->
            val top = if (index == 0) 20.dp else 6.dp
            val bottom = if (index == rows.lastIndex) 20.dp else 6.dp
            Surface(
                shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.4f),
                    )
                    Text(
                        text = if (secret && !reveal) "••••••••" else value,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.6f),
                    )
                }
            }
        }

        server.rawConfigJson?.let { raw ->
            Spacer(Modifier.size(8.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Конфиг Xray из подписки", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (reveal) prettyJson(raw) else "Скрыт: в нём ключи. Нажмите «Показать ключи».",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (reveal) FontFamily.Monospace else FontFamily.Default,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun prettyJson(raw: String): String = runCatching {
    val pretty = Json { prettyPrint = true }
    pretty.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw))
}.getOrDefault(raw)

/* ------------------------------------------------------------------ */
/* Лист подписки / группы                                              */
/* ------------------------------------------------------------------ */

@Composable
fun SubscriptionActionsSheet(
    state: VeilState,
    title: String,
    source: String?,
    servers: List<ProxyConfig>,
    collapsed: Boolean,
    onToggleCollapse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    ActionsSheet(
        icon = Icons.Rounded.CloudSync,
        title = title,
        subtitle = if (source == null) "${servers.size} серверов, добавлены вручную" else "${servers.size} серверов",
        qrText = source,
        qrCaption = "Ссылка подписки даёт доступ ко всем серверам — показывайте её только своим устройствам. " +
            "На приставке откройте «Принять с другого устройства» и отсканируйте код в Veil на телефоне.",
        onShareQr = source?.let { { state.share(it) } },
        details = null,
        buildActions = { sheet ->
            buildList {
                add(SheetAction(Icons.Rounded.Speed, "Проверить пинг группы", "Метод: ${state.pingMode.label}") {
                    sheet.close { state.pingSource(source) }
                })
                if (source != null) {
                    add(SheetAction(Icons.Rounded.Refresh, "Обновить подписку") {
                        sheet.close {
                            state.subscriptions.firstOrNull { it.url == source }?.let(state::refreshSubscription)
                        }
                    })
                    add(SheetAction(Icons.Rounded.QrCode2, "QR-код подписки", "Перенести на приставку или телефон") {
                        sheet.navigate(SheetPage.QR)
                    })
                    add(SheetAction(Icons.Rounded.ContentCopy, "Копировать ссылку") {
                        sheet.close {
                            clipboard.setText(AnnotatedString(source))
                            state.showBulletin("Ссылка скопирована", BulletinKind.SUCCESS)
                        }
                    })
                    add(SheetAction(Icons.Rounded.Share, "Поделиться") {
                        sheet.close { state.share(source) }
                    })
                }
                add(
                    SheetAction(
                        icon = if (collapsed) Icons.Rounded.UnfoldMore else Icons.Rounded.UnfoldLess,
                        title = if (collapsed) "Развернуть список" else "Свернуть список",
                    ) { sheet.close(onToggleCollapse) },
                )
                if (source != null) {
                    add(
                        SheetAction(
                            icon = Icons.Rounded.Delete,
                            title = "Удалить подписку",
                            subtitle = "Вместе со всеми её серверами",
                            destructive = true,
                            confirmText = "Удалить подписку «$title» и ${servers.size} серверов?",
                        ) {
                            sheet.close {
                                state.subscriptions.firstOrNull { it.url == source }?.let(state::removeSubscription)
                            }
                        },
                    )
                }
            }
        },
        onDismiss = onDismiss,
    )
}
