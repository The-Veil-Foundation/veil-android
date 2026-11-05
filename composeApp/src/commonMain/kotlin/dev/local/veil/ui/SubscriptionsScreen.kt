package dev.local.veil.ui

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
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription
import dev.local.veil.core.effectiveUserAgent
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
                SourceRow(
                    first = index == 0,
                    last = index == entries.lastIndex,
                    title = sub?.displayName ?: tr("Вручную", "Manual"),
                    subtitle = if (sub != null) sourceSubtitle(sub) else tr("Серверов: $manualCount", "Servers: $manualCount"),
                    icon = when {
                        sub == null -> AppIcons.Box
                        sub.hidden -> AppIcons.EyeOff
                        else -> AppIcons.Layers
                    },
                    pinned = sub?.pinned == true,
                    dimmed = sub?.hidden == true,
                    warning = sub != null && sub.nodeCount == 0,
                    usage = sub?.takeIf { it.totalBytes > 0 }?.let { (it.usedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f) },
                    onClick = { onOpen(entry.key) },
                )
            }
        }
    }
}

private fun sourceSubtitle(sub: Subscription): String = buildList {
    add(tr("Серверов: ${sub.nodeCount}", "Servers: ${sub.nodeCount}"))
    if (sub.hidden) add(tr("скрыт", "hidden"))
    if (sub.lastFetched > 0) add(formatAgo(sub.lastFetched))
    if (sub.expireAt > 0) add(tr("до ${formatDate(sub.expireAt)}", "until ${formatDate(sub.expireAt)}"))
}.joinToString(" · ")

@Composable
private fun SourceRow(
    first: Boolean,
    last: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    pinned: Boolean,
    dimmed: Boolean,
    warning: Boolean,
    usage: Float?,
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
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp).alpha(if (dimmed) 0.6f else 1f),
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
                    if (warning) {
                        AppIcon(AppIcons.Danger, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (usage != null) {
                    Spacer(Modifier.height(2.dp))
                    UsageBar(usage)
                }
            }
            AppIcon(AppIcons.ChevronRight, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.16f)
    val fill = if (fraction > 0.9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(4.dp).background(track, CircleShape)) {
        Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(4.dp).background(fill, CircleShape))
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
    var renaming by remember { mutableStateOf(false) }
    var editingAgent by remember { mutableStateOf(false) }
    var editingHwid by remember { mutableStateOf(false) }
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
            if (subscription != null) {
                Surface(
                    onClick = { state.refreshSubscription(subscription) },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    if (state.busy) {
                        Box(Modifier.padding(12.dp)) { ExpressiveLoader(diameter = 24.dp) }
                    } else {
                        AppIcon(AppIcons.Refresh, contentDescription = tr("Обновить", "Refresh"), modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (subscription != null) {
                item(key = "hero") { SourceHero(subscription) }

                item(key = "quick") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuickTile(AppIcons.Refresh, tr("Обновить", "Refresh"), Modifier.weight(1f)) {
                            state.refreshSubscription(subscription)
                        }
                        QuickTile(AppIcons.Copy, tr("Ссылка", "Link"), Modifier.weight(1f)) {
                            clipboard.setText(AnnotatedString(subscription.url))
                            state.showBulletin(tr("Ссылка скопирована", "Link copied"), BulletinKind.SUCCESS)
                        }
                        QuickTile(AppIcons.Bolt, tr("Группа", "Group"), Modifier.weight(1f)) {
                            state.makeSourceGroup(subscription)
                        }
                    }
                }

                if (subscription.skipped.isNotEmpty()) {
                    item(key = "skipped") { SkippedCard(subscription.skipped) }
                }

                item(key = "custom-title") { SectionLabel(tr("Настройка", "Customize")) }
                item(key = "custom") {
                    SegmentedGroup(
                        rows = buildList<@Composable () -> Unit> {
                        listOf<@Composable () -> Unit>(
                            {
                                SegmentAction(
                                    title = tr("Название", "Name"),
                                    subtitle = subscription.name.ifBlank {
                                        tr("Как у панели: ${subscription.title.ifBlank { subscription.host }}", "From the panel: ${subscription.title.ifBlank { subscription.host }}")
                                    },
                                    icon = AppIcons.Edit,
                                    onClick = { renaming = true },
                                )
                            },
                            {
                                SegmentSwitch(
                                    title = tr("Закрепить", "Pin"),
                                    subtitle = tr("Держать выше остальных источников", "Keep above other sources"),
                                    icon = AppIcons.Pin,
                                    checked = subscription.pinned,
                                    onCheckedChange = { on -> state.updateSubscription(subscription.url) { it.copy(pinned = on) } },
                                )
                            },
                            {
                                SegmentSwitch(
                                    title = tr("Показывать в серверах", "Show in servers"),
                                    subtitle = if (subscription.hidden) {
                                        tr("Скрыт: серверы не видны в списке, но не удалены", "Hidden: servers are out of the list but not deleted")
                                    } else {
                                        tr("Серверы источника видны в списке", "This source's servers are listed")
                                    },
                                    icon = AppIcons.Eye,
                                    checked = !subscription.hidden,
                                    onCheckedChange = { on -> state.updateSubscription(subscription.url) { it.copy(hidden = !on) } },
                                )
                            },
                            {
                                SegmentSwitch(
                                    title = tr("Автообновление", "Auto refresh"),
                                    subtitle = tr("Обновлять вместе с остальными по расписанию", "Refresh with the others on schedule"),
                                    icon = AppIcons.AutoRefresh,
                                    checked = subscription.autoUpdate,
                                    onCheckedChange = { on -> state.updateSubscription(subscription.url) { it.copy(autoUpdate = on) } },
                                )
                            },
                            {
                                SegmentAction(
                                    title = "User-Agent",
                                    subtitle = subscription.userAgent.ifBlank {
                                        tr("Общий: ${effectiveUserAgent(state.settings.userAgent)}", "Shared: ${effectiveUserAgent(state.settings.userAgent)}")
                                    },
                                    icon = AppIcons.Global,
                                    onClick = { editingAgent = true },
                                )
                            },
                            {
                                SegmentChoice(
                                    title = "HWID",
                                    subtitle = tr("Слать ли этой панели ID устройства", "Whether this panel gets the device ID"),
                                    icon = AppIcons.Shield,
                                    options = listOf(
                                        SegmentOption(tr("как везде", "default"), subscription.sendHwid == null) {
                                            state.updateSubscription(subscription.url) { it.copy(sendHwid = null) }
                                        },
                                        SegmentOption(tr("да", "on"), subscription.sendHwid == true) {
                                            state.updateSubscription(subscription.url) { it.copy(sendHwid = true) }
                                        },
                                        SegmentOption(tr("нет", "off"), subscription.sendHwid == false) {
                                            state.updateSubscription(subscription.url) { it.copy(sendHwid = false) }
                                        },
                                    ),
                                )
                            },
                        ).forEach { add(it) }
                        if (state.hwidFor(subscription) != null) {
                            add {
                                SegmentAction(
                                    title = tr("Свой HWID", "Own HWID"),
                                    subtitle = subscription.hwid.ifBlank {
                                        tr("Общий: ${state.deviceId}", "Shared: ${state.deviceId}")
                                    },
                                    icon = AppIcons.Edit,
                                    onClick = { editingHwid = true },
                                )
                            }
                            add {
                                SegmentAction(
                                    title = tr("Выпустить свой HWID", "Issue own HWID"),
                                    subtitle = tr("Отдельный ID только для этой панели", "A separate ID for this panel only"),
                                    icon = AppIcons.Refresh,
                                    onClick = {
                                        state.updateSubscription(subscription.url) { it.copy(hwid = dev.local.veil.core.DeviceIds.mint()) }
                                    },
                                )
                            }
                        }
                    },
                    )
                }

                if (state.subscriptions.size > 1 && !subscription.pinned) {
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
                            add { SegmentInfo(tr("Адрес", "Address"), maskUrl(subscription.url)) }
                            if (subscription.lastFormat.isNotBlank()) {
                                add { SegmentInfo(tr("Формат", "Format"), subscription.lastFormat) }
                            }
                            add {
                                SegmentInfo(
                                    tr("Обновлён", "Updated"),
                                    subscription.lastFetched.takeIf { it > 0 }?.let { formatAgo(it) } ?: tr("никогда", "never"),
                                )
                            }
                            if (subscription.webUrl.isNotBlank()) {
                                add {
                                    SegmentAction(
                                        title = tr("Сайт", "Website"),
                                        subtitle = subscription.webUrl,
                                        icon = AppIcons.Link,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(subscription.webUrl))
                                            state.showBulletin(tr("Адрес скопирован", "Address copied"), BulletinKind.SUCCESS)
                                        },
                                    )
                                }
                            }
                            if (subscription.supportUrl.isNotBlank()) {
                                add {
                                    SegmentAction(
                                        title = tr("Поддержка", "Support"),
                                        subtitle = subscription.supportUrl,
                                        icon = AppIcons.Link,
                                        onClick = {
                                            clipboard.setText(AnnotatedString(subscription.supportUrl))
                                            state.showBulletin(tr("Адрес скопирован", "Address copied"), BulletinKind.SUCCESS)
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item(key = "servers-title") { SectionLabel(tr("Серверы · ${nodes.size}", "Servers · ${nodes.size}")) }
            if (nodes.isEmpty()) {
                item(key = "no-nodes") {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            tr("Источник не вернул ни одного сервера. Обновите его или проверьте ссылку.", "The source returned no servers. Refresh it or check the link."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
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
                if (subscription != null) {
                    item(key = "nodes-note") {
                        Text(
                            tr("Серверы подписки принадлежат панели. Имя, теги и скрытие — ваши и переживут обновление; чтобы изменить сам узел, продублируйте его во «Вручную».", "A subscription's servers belong to the panel. Names, tags and hiding are yours and survive a refresh; to change the server itself, duplicate it into Manual."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp),
                        )
                    }
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
                        Text(tr("Удалить источник", "Delete source"), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    sheetServer?.let { server ->
        ServerActionsSheet(state = state, status = status, server = server, onDismiss = { sheetServer = null })
    }

    if (subscription != null && renaming) {
        TextDialog(
            title = tr("Название источника", "Source name"),
            initial = subscription.name,
            placeholder = subscription.title.ifBlank { subscription.host },
            hint = tr("Пусто — название от панели.", "Empty — the panel's name."),
            onDismiss = { renaming = false },
            onSave = { value ->
                state.updateSubscription(subscription.url) { it.copy(name = value.trim()) }
                renaming = false
            },
        )
    }

    if (subscription != null && editingAgent) {
        TextDialog(
            title = "User-Agent",
            initial = subscription.userAgent,
            placeholder = effectiveUserAgent(state.settings.userAgent),
            hint = tr("Панель отдаёт формат по User-Agent. Пусто — общий из настроек.", "The panel picks the format by User-Agent. Empty — the shared one from settings."),
            onDismiss = { editingAgent = false },
            onSave = { value ->
                state.updateSubscription(subscription.url) { it.copy(userAgent = value.trim()) }
                editingAgent = false
            },
        )
    }

    if (subscription != null && editingHwid) {
        TextDialog(
            title = tr("HWID для этой панели", "HWID for this panel"),
            initial = subscription.hwid.ifBlank { state.deviceId },
            placeholder = state.deviceId,
            hint = tr("Пусто или общий ID — эта панель получает общий ID устройства. Буквы станут заглавными.", "Empty or the shared ID — this panel gets the shared device ID. Letters become uppercase."),
            onDismiss = { editingHwid = false },
            onSave = { value ->
                val own = dev.local.veil.core.DeviceIds.normalizedManual(value)?.takeIf { it != state.deviceId }.orEmpty()
                state.updateSubscription(subscription.url) { it.copy(hwid = own) }
                editingHwid = false
            },
        )
    }

    if (subscription != null && confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr("Удалить источник?", "Delete source?"), fontWeight = FontWeight.Black) },
            text = { Text(tr("Серверов уйдёт: ${nodes.size}. Это нельзя отменить.", "${nodes.size} servers will be removed. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    state.removeSubscription(subscription)
                    onBack()
                }) { Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }
}

/** Шапка источника: трафик, срок и объявление панели. */
@Composable
private fun SourceHero(subscription: Subscription) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Трафик", "Traffic"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                    val used = subscription.usedBytes.coerceAtLeast(0)
                    Text(
                        when {
                            subscription.totalBytes > 0 -> "${formatBytes(used)} / ${formatBytes(subscription.totalBytes)}"
                            used > 0 -> tr("${formatBytes(used)} · без лимита", "${formatBytes(used)} · unlimited")
                            else -> tr("Без лимита", "Unlimited")
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        tr("Срок", "Expires"),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    )
                    Text(
                        subscription.expireAt.takeIf { it > 0 }?.let { formatDate(it) } ?: tr("бессрочно", "never"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            // Безлимит — полная полоса: тратить можно сколько угодно.
            UsageBar(
                if (subscription.totalBytes > 0) (subscription.usedBytes.toFloat() / subscription.totalBytes).coerceIn(0f, 1f) else 1f,
            )
            if (subscription.expireAt > 0) {
                Text(
                    formatRemaining(subscription.expireAt),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
            }
            if (subscription.description.isNotBlank()) {
                Text(
                    subscription.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun QuickTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Column(
            Modifier.padding(vertical = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppIcon(icon, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** Что панель вернула, но мы не смогли использовать — чтобы было видно, куда делся узел. */
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
            skipped.groupingBy { it }.eachCount().entries.take(8).forEach { (label, count) ->
                Text(
                    if (count > 1) "· $label × $count" else "· $label",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
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
