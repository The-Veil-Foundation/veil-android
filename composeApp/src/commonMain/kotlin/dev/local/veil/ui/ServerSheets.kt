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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
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
    // Высота листа меняется вместе со страницей; на низком экране (TV,
    // ландшафт) лист оставался на старой отметке и обрезал низ страницы.
    LaunchedEffect(page) {
        if (page != SheetPage.ACTIONS && sheetState.isVisible) {
            delay(220)
            runCatching { sheetState.expand() }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
    ) {
        // Лист прокручивается целиком: в горизонтальном положении пункты не
        // помещаются по высоте, и последний — красное «Удалить» — пропадал.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
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
                                            shape = MaterialTheme.shapes.large,
                                        ) { Text(tr("Удалить", "Delete")) }
                                        TextButton(onClick = { confirming = null }) { Text(tr("Отмена", "Cancel")) }
                                    }
                                }
                            }
                        }
                    }

                    SheetPage.DETAILS -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PageBar(title = tr("Подробности", "Details"), onBack = { page = SheetPage.ACTIONS }) {
                            TextButton(modifier = Modifier.tvFocusRing(CircleShape), onClick = { reveal = !reveal }) {
                                AppIcon(
                                    if (reveal) AppIcons.EyeOff else AppIcons.Eye,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(if (reveal) tr("Скрыть ключи", "Hide keys") else tr("Показать ключи", "Show keys"))
                            }
                        }
                        val detailScroll = rememberScrollState()
                        Column(
                            Modifier
                                .heightIn(max = if (LocalTvMode.current) 300.dp else 460.dp)
                                .tvKeyScroll(detailScroll)
                                .verticalScroll(detailScroll),
                        ) {
                            details?.invoke(reveal)
                        }
                    }

                    SheetPage.QR -> Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        PageBar(title = tr("QR-код", "QR code"), onBack = { page = SheetPage.ACTIONS }) {}
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
                                AppIcon(AppIcons.Share, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(6.dp))
                                Text(tr("Поделиться ссылкой", "Share link"))
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
            AppIcon(icon, tint = MaterialTheme.colorScheme.onPrimaryContainer)
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
        IconButton(modifier = Modifier.tvFocusRing(CircleShape), onClick = onBack) {
            AppIcon(AppIcons.ArrowLeft, contentDescription = tr("Назад", "Back"))
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
    val top = if (index == 0) 28.dp else 10.dp
    val bottom = if (index == last) 28.dp else 10.dp
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
            .tvFocusRing(RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom))
            .staggerIn(seen, "tile-$index", index)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppIcon(action.icon)
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
    var editingConfig by remember { mutableStateOf(false) }
    // «Конфигурация» закрывает лист и открывает своё окно.
    // Окно живёт в этом же composable, поэтому вызывающему сообщаем о закрытии
    // только когда закрыто и оно: иначе он уберёт лист вместе с окном.
    var sheetGone by remember { mutableStateOf(false) }
    LaunchedEffect(sheetGone, editingConfig) {
        if (sheetGone && !editingConfig) onDismiss()
    }
    val ping = state.pings[server.id]
    val endpoint = if (server.balanced > 1) {
        tr("авто из ${server.balanced} транспортов", "auto from ${server.balanced} transports")
    } else {
        server.endpoint
    }

    if (!sheetGone) ActionsSheet(
        icon = AppIcons.Server,
        title = state.displayName(server),
        subtitle = buildString {
            append(server.proto.wire.uppercase()).append(" · ").append(endpoint)
            if (ping != null) append(" · ").append(ping).append(tr(" мс", " ms"))
        },
        qrText = link,
        qrCaption = tr("Ссылка содержит ключи доступа — показывайте её только своим устройствам.", "The link contains access keys — only show it to your own devices."),
        onShareQr = link?.let { { state.share(it) } },
        details = { reveal -> ServerDetails(server, reveal) },
        buildActions = { sheet ->
            buildList {
                add(SheetAction(AppIcons.Eye, tr("Посмотреть", "View"), tr("Параметры узла", "Server details")) {
                    sheet.navigate(SheetPage.DETAILS)
                })
                // Редактор узла с macOS: JSON, ссылка или конфиг WireGuard.
                // Свой узел правится и проверяется ядром, узел подписки читается.
                add(
                    SheetAction(
                        icon = AppIcons.Document,
                        title = tr("Конфигурация", "Configuration"),
                        subtitle = if (server.source == null) {
                            tr("JSON или ссылка — изменить и проверить ядром", "JSON or link — edit and check with the core")
                        } else {
                            tr("JSON или ссылка, как её запустит ядро", "JSON or link, as the core will run it")
                        },
                    ) { sheet.close { editingConfig = true } },
                )
                if (server.source != null) {
                    add(
                        SheetAction(
                            icon = AppIcons.Edit,
                            title = tr("Дублировать во «Вручную»", "Duplicate into Manual"),
                            subtitle = tr("Копию можно менять, подписка не тронется", "The copy is yours to change; the subscription stays as is"),
                        ) { sheet.close { state.duplicateToManual(server) } },
                    )
                }
                if (link != null) {
                    add(SheetAction(AppIcons.Qr, tr("QR-код", "QR code"), tr("Перенести на другое устройство", "Transfer to another device")) {
                        sheet.navigate(SheetPage.QR)
                    })
                    add(SheetAction(AppIcons.Copy, tr("Копировать ссылку", "Copy link")) {
                        sheet.close {
                            clipboard.setText(AnnotatedString(link))
                            state.showBulletin(tr("Ссылка скопирована", "Link copied"), BulletinKind.SUCCESS)
                        }
                    })
                }
                add(
                    SheetAction(
                        icon = AppIcons.Trash,
                        title = tr("Удалить", "Delete"),
                        destructive = true,
                        confirmText = tr("Удалить «${server.name}»? Это нельзя отменить.", "Delete “${server.name}”? This cannot be undone."),
                    ) { sheet.close { state.removeServer(server.id) } },
                )
            }
        },
        onDismiss = { sheetGone = true },
    )

    if (editingConfig) {
        NodeEditorDialog(
            state = state,
            server = server,
            editable = server.source == null,
            onDismiss = { editingConfig = false },
        )
    }
}

/** Параметры узла. Ключи скрыты, пока их явно не покажут. */
@Composable
private fun ServerDetails(server: ProxyConfig, reveal: Boolean) {
    val rows = buildList {
        add(Triple(tr("Протокол", "Protocol"), server.proto.wire.uppercase(), false))
        add(Triple(tr("Адрес", "Address"), server.endpoint, false))
        if (server.balanced > 1) add(Triple(tr("Транспортов", "Transports"), tr("${server.balanced} (балансировщик)", "${server.balanced} (balancer)"), false))
        add(Triple(tr("Транспорт", "Transport"), server.network.wire, false))
        add(Triple(tr("Защита", "Security"), server.security.wire, false))
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
        server.password?.let { add(Triple(tr("Пароль", "Password"), it, true)) }
        server.privateKey?.let { add(Triple(tr("Приватный ключ", "Private key"), it, true)) }
        server.presharedKey?.let { add(Triple("Preshared key", it, true)) }
        server.source?.let { add(Triple(tr("Подписка", "Subscription"), it.substringAfter("://").substringBefore('/'), false)) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        rows.forEachIndexed { index, (label, value, secret) ->
            val top = if (index == 0) 28.dp else 10.dp
            val bottom = if (index == rows.lastIndex) 28.dp else 10.dp
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
                    Text(tr("Конфиг Xray из подписки", "Xray config from subscription"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (reveal) prettyJson(raw) else tr("Скрыт: в нём ключи. Нажмите «Показать ключи».", "Hidden: it contains keys. Tap “Show keys”."),
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
        icon = AppIcons.Cloud,
        title = title,
        subtitle = if (source == null) tr("${servers.size} серверов, добавлены вручную", "${servers.size} servers, added manually") else tr("${servers.size} серверов", "${servers.size} servers"),
        qrText = source,
        qrCaption = tr("Ссылка подписки даёт доступ ко всем серверам — показывайте её только своим устройствам. ", "The subscription link grants access to all servers — only show it to your own devices. ") +
            tr("На приставке откройте «Принять с другого устройства» и отсканируйте код в Veil на телефоне.", "On the TV box open “Receive from another device” and scan the code in Veil on your phone."),
        onShareQr = source?.let { { state.share(it) } },
        details = null,
        buildActions = { sheet ->
            buildList {
                // Пинг группы и сворачивание — уже кнопки на самой карточке, здесь не дублируем.
                if (source != null) {
                    add(SheetAction(AppIcons.Refresh, tr("Обновить подписку", "Refresh subscription")) {
                        sheet.close {
                            state.subscriptions.firstOrNull { it.url == source }?.let(state::refreshSubscription)
                        }
                    })
                    add(SheetAction(AppIcons.Qr, tr("QR-код подписки", "Subscription QR code"), tr("Перенести на приставку или телефон", "Transfer to TV box or phone")) {
                        sheet.navigate(SheetPage.QR)
                    })
                    add(SheetAction(AppIcons.Copy, tr("Копировать ссылку", "Copy link")) {
                        sheet.close {
                            clipboard.setText(AnnotatedString(source))
                            state.showBulletin(tr("Ссылка скопирована", "Link copied"), BulletinKind.SUCCESS)
                        }
                    })
                }
                if (source != null) {
                    add(
                        SheetAction(
                            icon = AppIcons.Trash,
                            title = tr("Удалить подписку", "Delete subscription"),
                            subtitle = tr("Вместе со всеми её серверами", "Along with all its servers"),
                            destructive = true,
                            confirmText = tr("Удалить подписку «$title» и ${servers.size} серверов?", "Delete subscription “$title” and ${servers.size} servers?"),
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
