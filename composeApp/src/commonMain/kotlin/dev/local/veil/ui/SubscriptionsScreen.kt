package dev.local.veil.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import dev.local.veil.core.NodeRepresentation
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import dev.local.veil.platform.VpnStatus

/** Ключ «источника» для узлов, добавленных вручную. */
private const val MANUAL = "manual"

/**
 * Источники — порт SourcesView из veil-macos: откуда взялись серверы, что
 * вернула панель и что с этим источником можно сделать.
 */
@Composable
fun SubscriptionsScreen(
    state: VeilState,
    status: VpnStatus,
    modifier: Modifier = Modifier,
    onAdd: () -> Unit = {},
    onOpenReceive: () -> Unit = {},
) {
    var opened by remember { mutableStateOf<String?>(null) }

    SourcesList(
        state = state,
        modifier = modifier,
        onOpen = { opened = it },
        onAdd = onAdd,
        onOpenReceive = onOpenReceive,
    )

    val key = opened
    val subscription = key?.let { url -> state.subscriptions.firstOrNull { it.url == url } }
    val manualOpen = key == MANUAL && state.servers.any { it.source == null }
    if (subscription != null || manualOpen) {
        Dialog(
            onDismissRequest = { opened = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val background = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxSize().background(background)) {
                Surface(Modifier.fillMaxSize().statusBarsPadding(), color = background) {
                    SourceDetail(
                        state = state,
                        status = status,
                        subscription = subscription,
                        modifier = Modifier.fillMaxSize(),
                        onBack = { opened = null },
                    )
                }
                // Экран источника — отдельное окно поверх приложения, и общие
                // уведомления оказались бы под ним: «Копия добавлена» не видна.
                BulletinHost(
                    bulletin = state.bulletin,
                    onDismiss = state::dismissBulletin,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Список источников                                                   */
/* ------------------------------------------------------------------ */

@Composable
private fun SourcesList(
    state: VeilState,
    modifier: Modifier,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onOpenReceive: () -> Unit,
) {
    val subscriptions = state.orderedSubscriptions
    val manualCount = state.servers.count { it.source == null }

    if (subscriptions.isEmpty() && manualCount == 0) {
        EmptySources(onAdd = onAdd, onOpenReceive = onOpenReceive, modifier = modifier)
        return
    }

    data class Entry(val key: String, val subscription: Subscription?)
    val entries = subscriptions.map { Entry(it.url, it) } +
        if (manualCount > 0) listOf(Entry(MANUAL, null)) else emptyList()

    RefreshableBox(
        enabled = !state.isTv && subscriptions.isNotEmpty(),
        isRefreshing = state.busy,
        onRefresh = { state.refreshAll() },
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(entries, key = { _, it -> it.key }) { index, entry ->
                val sub = entry.subscription
                val count = if (sub != null) state.servers.count { it.source == sub.url } else manualCount
                SourceRow(
                    first = index == 0,
                    last = index == entries.lastIndex,
                    title = sub?.displayName ?: tr("Вручную", "Manual"),
                    subtitle = tr("Серверов: $count", if (count == 1) "1 server" else "$count servers"),
                    icon = if (sub == null) AppIcons.Box else AppIcons.Layers,
                    pinned = sub?.pinned == true,
                    onClick = { onOpen(entry.key) },
                )
            }
        }
    }
}

@Composable
private fun SourceRow(
    first: Boolean,
    last: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    pinned: Boolean,
    onClick: () -> Unit,
) {
    val top = if (first) 24.dp else 6.dp
    val bottom = if (last) 24.dp else 6.dp
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(icon, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (pinned) {
                        AppIcon(AppIcons.Pin, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppIcon(AppIcons.ChevronRight, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Ни одного источника: одна понятная точка входа, как на экране серверов. */
@Composable
private fun EmptySources(onAdd: () -> Unit, onOpenReceive: () -> Unit, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Box(
                Modifier.size(88.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                AppIcon(AppIcons.Layers, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Источников пока нет", "No sources yet"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
            )
            Text(
                tr("Подписка — ссылка от вашего провайдера. Её серверы обновляются сами, а здесь видно трафик и срок.", "A subscription is a link from your provider. Its servers update themselves, and here you see traffic and expiry."),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = onAdd, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                AppIcon(AppIcons.Add, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(tr("Добавить подписку", "Add subscription"), fontWeight = FontWeight.Bold)
            }
            TextButton(onClick = onOpenReceive) {
                AppIcon(AppIcons.Qr, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(tr("Принять с другого устройства", "Receive from another device"))
            }
        }
    }
}

/* ------------------------------------------------------------------ */
/* Экран источника                                                     */
/* ------------------------------------------------------------------ */

/**
 * Один источник, как деталь SourcesView на macOS: сводка, что не удалось
 * прочитать, и узлы. Действия — те же, что в контекстном меню источника там.
 */
@Composable
private fun SourceDetail(
    state: VeilState,
    status: VpnStatus,
    subscription: Subscription?,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var confirmRemove by remember { mutableStateOf(false) }
    var sheetServer by remember { mutableStateOf<ProxyConfig?>(null) }
    val source = subscription?.url
    val nodes = state.servers.filter { it.source == source }
    val title = subscription?.displayName ?: tr("Вручную", "Manual")

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(onClick = onBack, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                AppIcon(AppIcons.ArrowLeft, contentDescription = tr("Назад", "Back"), modifier = Modifier.padding(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (subscription != null) item(key = "actions") {
                SegmentedGroup(
                    rows = buildList<@Composable () -> Unit> {
                        if (subscription != null) {
                            add {
                                SegmentAction(
                                    title = if (state.busy) tr("Обновляем…", "Refreshing…") else tr("Обновить сейчас", "Refresh now"),
                                    icon = AppIcons.Refresh,
                                    enabled = !state.busy,
                                    onClick = { state.refreshSubscription(subscription) },
                                )
                            }
                            add {
                                SegmentAction(
                                    title = if (subscription.pinned) tr("Открепить", "Unpin") else tr("Закрепить", "Pin"),
                                    icon = AppIcons.Pin,
                                    onClick = { state.updateSubscription(subscription.url) { it.copy(pinned = !it.pinned) } },
                                )
                            }
                            add {
                                SegmentAction(
                                    title = tr("Скопировать ссылку подписки", "Copy subscription link"),
                                    icon = AppIcons.Copy,
                                    onClick = {
                                        clipboard.setText(AnnotatedString(subscription.url))
                                        state.showBulletin(tr("Ссылка скопирована", "Link copied"), BulletinKind.SUCCESS)
                                    },
                                )
                            }
                        }
                    },
                )
            }

            if (subscription != null && state.subscriptions.size > 1 && !subscription.pinned) {
                item(key = "order-title") { SectionLabel(tr("Порядок", "Order")) }
                item(key = "order") {
                    val index = state.subscriptions.indexOfFirst { it.url == subscription.url }
                    SegmentedGroup(
                        rows = listOf(
                            {
                                SegmentAction(
                                    title = tr("Выше", "Move up"),
                                    icon = AppIcons.Up,
                                    enabled = index > 0,
                                    onClick = { state.moveSubscription(subscription.url, -1) },
                                )
                            },
                            {
                                SegmentAction(
                                    title = tr("Ниже", "Move down"),
                                    icon = AppIcons.Down,
                                    enabled = index >= 0 && index < state.subscriptions.lastIndex,
                                    onClick = { state.moveSubscription(subscription.url, 1) },
                                )
                            },
                        ),
                    )
                }
            }

            item(key = "info-title") { SectionLabel(tr("Сведения", "Details")) }
            item(key = "info") {
                SegmentedGroup(
                    rows = buildList<@Composable () -> Unit> {
                        if (subscription != null) {
                            add { SegmentInfo(tr("Адрес", "Address"), maskUrl(subscription.url)) }
                        }
                        add { SegmentInfo(tr("Серверы", "Servers"), nodes.size.toString()) }
                        if (subscription != null) {
                            if (subscription.lastFormat.isNotBlank()) {
                                add { SegmentInfo(tr("Формат", "Format"), subscription.lastFormat) }
                            }
                            if (subscription.lastFetched > 0) {
                                add { SegmentInfo(tr("Обновлён", "Last updated"), formatAgo(subscription.lastFetched)) }
                            }
                            if (subscription.expireAt > 0) {
                                add { SegmentInfo(tr("Истекает", "Expires"), formatDate(subscription.expireAt)) }
                            }
                            if (subscription.usedBytes > 0 || subscription.totalBytes > 0) {
                                val used = formatBytes(subscription.usedBytes.coerceAtLeast(0))
                                add {
                                    SegmentInfo(
                                        tr("Трафик", "Traffic"),
                                        if (subscription.totalBytes > 0) "$used / ${formatBytes(subscription.totalBytes)}" else used,
                                    )
                                }
                            }
                        }
                    },
                )
            }

            if (subscription != null && subscription.skipped.isNotEmpty()) {
                item(key = "skipped") { SkippedCard(subscription.skipped) }
            }

            if (subscription != null) {
                item(key = "note") {
                    Text(
                        tr(
                            "Серверы подписки принадлежат панели. Имя, теги и закрепление — ваши и переживут обновление; чтобы изменить сам узел, продублируйте его во «Вручную».",
                            "A subscription's servers are the panel's. Rename them, tag them, pin them — that is yours and survives a refresh — but to change what one connects to, duplicate it into Manual.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
                    )
                }
            }

            item(key = "servers-title") { SectionLabel(tr("Серверы", "Servers")) }
            if (nodes.isEmpty()) {
                item(key = "no-nodes") {
                    Text(
                        tr("В этом источнике нет серверов.", "This source has no servers in it."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
            } else {
                item(key = "nodes") {
                    SegmentedGroup(
                        rows = nodes.map { node ->
                            {
                                SourceNodeRow(
                                    node = node,
                                    name = state.displayName(node),
                                    hidden = state.annotationFor(node.id).hidden,
                                    onClick = { sheetServer = node },
                                )
                            }
                        },
                    )
                }
            }

            if (subscription != null) {
                item(key = "delete") {
                    TextButton(
                        onClick = { confirmRemove = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                        shape = CircleShape,
                    ) {
                        AppIcon(AppIcons.Trash, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.size(8.dp))
                        Text(tr("Удалить", "Remove"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    sheetServer?.let { server ->
        ServerActionsSheet(state = state, status = status, server = server, onDismiss = { sheetServer = null })
    }

    if (subscription != null && confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr("Удалить источник?", "Remove source?"), fontWeight = FontWeight.Black) },
            text = { Text(tr("Серверов уйдёт: ${nodes.size}. Это нельзя отменить.", "${nodes.size} servers will be removed. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    state.removeSubscription(subscription)
                    onBack()
                }) { Text(tr("Удалить", "Remove"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** Что панель вернула, но прочитать не вышло — чтобы было видно, куда делся узел. */
@Composable
private fun SkippedCard(skipped: List<String>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(AppIcons.Danger, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    tr("Не всё из источника удалось использовать", "Not everything in this source could be used"),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            skipped.groupingBy { it }.eachCount().entries.forEach { (label, count) ->
                Text(
                    "· $label × $count",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                tr(
                    "Эти записи отброшены при чтении источника. Если сервера нет в списке, причина указана здесь.",
                    "These entries were dropped while reading the source. A server that is missing from the list is listed here with the reason.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Узел в том виде, в каком его хотят читать                           */
/* ------------------------------------------------------------------ */

private fun kindTitle(kind: NodeRepresentation.Kind): String = when (kind) {
    NodeRepresentation.Kind.WIREGUARD -> tr("Конфиг WireGuard", "WireGuard config")
    NodeRepresentation.Kind.JSON -> "Outbound JSON"
    NodeRepresentation.Kind.LINK -> tr("Ссылка", "Share link")
}

/**
 * Порт NodeEditorSheet из veil-macos: узел как конфиг WireGuard, outbound JSON
 * или ссылка. Свой узел («Вручную») можно править и проверить ядром, узел
 * подписки — только прочитать или продублировать во «Вручную».
 */
@Composable
internal fun NodeEditorDialog(
    state: VeilState,
    server: ProxyConfig,
    editable: Boolean,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val kinds = remember(server) { NodeRepresentation.kinds(server) }
    var kind by remember(server) { mutableStateOf(kinds.first()) }
    var text by remember(server) { mutableStateOf(NodeRepresentation.text(kinds.first(), server, state.settings.coreEngine)) }
    var message by remember { mutableStateOf<String?>(null) }
    var bad by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    fun parsed(): ProxyConfig? = try {
        NodeRepresentation.parse(kind, text, server)
    } catch (e: NodeRepresentation.Failure) {
        message = tr(e.ru, e.en); bad = true; null
    } catch (e: Exception) {
        message = e.message ?: e.toString(); bad = true; null
    }

    // Спрашивает настоящее ядро: сообщение, которое читает человек, — его собственное.
    suspend fun check(): ProxyConfig? {
        val node = parsed() ?: return null
        checking = true
        val outcome = state.checkNode(node)
        checking = false
        return when (outcome) {
            VeilState.NodeCheck.Ok -> {
                message = tr("Ядро приняло эту конфигурацию.", "The core accepted this configuration."); bad = false; node
            }
            is VeilState.NodeCheck.Failed -> {
                message = outcome.message; bad = true; null
            }
            // Сделать с отсутствующим ядром человек ничего не может, а отказ
            // сохранить потерял бы его правку.
            VeilState.NodeCheck.Unavailable -> {
                message = tr("Ядра нет, поэтому конфигурация не проверена.", "The core is not available, so this was not checked."); bad = false; node
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    state.displayName(server),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { option ->
                        FilterChip(
                            selected = kind == option,
                            onClick = {
                                kind = option
                                text = NodeRepresentation.text(option, server, state.settings.coreEngine)
                                message = null
                            },
                            label = { Text(kindTitle(option), maxLines = 1) },
                            modifier = Modifier.tvFocusRing(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                if (editable) {
                    OutlinedTextField(
                        colors = veilFieldColors(),
                        value = text,
                        onValueChange = { text = it },
                        readOnly = !editable,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(16.dp),
                        // Поле сжимается, а не кнопки уезжают: на телевизоре и в
                        // ландшафте экран ниже, чем JSON узла.
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 360.dp),
                    )
                } else {
                    // Только чтение: не поле ввода, а прокручиваемый текст. В поле
                    // пульт двигает курсор по JSON и не может выйти к кнопкам.
                    val textScroll = rememberScrollState()
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 120.dp, max = 360.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .tvKeyScroll(textScroll)
                                    .verticalScroll(textScroll)
                                    .padding(16.dp),
                            )
                        }
                    }
                }
                message?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(modifier = Modifier.tvFocusRing(CircleShape), onClick = {
                        clipboard.setText(AnnotatedString(text))
                        // Окно закрывает общие уведомления: отвечаем прямо в нём.
                        message = tr("Скопировано", "Copied"); bad = false
                    }) { Text(tr("Копировать", "Copy")) }
                    if (!editable) {
                        TextButton(modifier = Modifier.tvFocusRing(CircleShape), onClick = {
                            state.duplicateToManual(server)
                            onDismiss()
                        }) { Text(tr("Дублировать", "Duplicate")) }
                    }
                    Spacer(Modifier.weight(1f))
                    if (checking) ExpressiveLoader(diameter = 20.dp)
                    if (editable) {
                        TextButton(modifier = Modifier.tvFocusRing(CircleShape), enabled = !checking, onClick = { scope.launch { check() } }) {
                            Text(tr("Проверить", "Check"))
                        }
                        TextButton(modifier = Modifier.tvFocusRing(CircleShape), enabled = !checking, onClick = {
                            scope.launch {
                                val node = check() ?: return@launch
                                state.replaceServer(node)
                                onDismiss()
                            }
                        }) { Text(tr("Сохранить", "Save"), fontWeight = FontWeight.Bold) }
                    }
                    TextButton(modifier = Modifier.tvFocusRing(CircleShape), onClick = onDismiss) { Text(tr("Закрыть", "Close")) }
                }
            }
        }
    }
}

@Composable
internal fun TextDialog(
    title: String,
    initial: String,
    placeholder: String,
    hint: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    colors = veilFieldColors(),
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text(tr("Сохранить", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

@Composable
private fun SourceNodeRow(node: ProxyConfig, name: String, hidden: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (hidden) 0.55f else 1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (node.isUsable) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.errorContainer,
            ) {
                Text(
                    node.proto.wire.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (node.isUsable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    node.endpoint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (hidden) {
                AppIcon(AppIcons.EyeOff, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** URL подписки — это credential, целиком в интерфейсе его показывать нельзя. */
private fun maskUrl(url: String): String {
    val tail = url.takeLast(4)
    return "${url.substringAfter("://").substringBefore('/')}/…$tail"
}
