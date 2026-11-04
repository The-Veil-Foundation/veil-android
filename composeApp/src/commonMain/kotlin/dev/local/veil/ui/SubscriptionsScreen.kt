package dev.local.veil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.local.veil.core.AppStyle
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.Subscription

/**
 * Источники — порт SourcesView / SourceDetailView из veil-ios:
 * список подписок и экран одной подписки с её узлами.
 */
@Composable
fun SubscriptionsScreen(
    state: VeilState,
    modifier: Modifier = Modifier,
) {
    var opened by remember { mutableStateOf<String?>(null) }

    SourcesList(state = state, modifier = modifier, onOpen = { opened = it.url })

    val subscription = opened?.let { url -> state.subscriptions.firstOrNull { it.url == url } }
    if (subscription != null) {
        Dialog(
            onDismissRequest = { opened = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            val background = MaterialTheme.colorScheme.background
            Box(Modifier.fillMaxSize().background(background)) {
                Surface(
                    Modifier.fillMaxSize().statusBarsPadding(),
                    color = background,
                ) {
                    SourceDetail(
                        state = state,
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SourcesList(
    state: VeilState,
    modifier: Modifier,
    onOpen: (Subscription) -> Unit,
) {
    val subscriptions = state.subscriptions

    if (subscriptions.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppIcon(
                    AppIcons.Box,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr("Подписок пока нет", "No subscriptions yet"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    tr("Нажмите + , чтобы добавить ссылку", "Tap + to add a link"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        return
    }

    RefreshableBox(
        enabled = !state.isTv,
        isRefreshing = state.busy,
        onRefresh = { state.refreshAll() },
        modifier = modifier.fillMaxSize(),
    ) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(
            if (LocalAppStyle.current == AppStyle.IOS) 0.dp else 2.dp,
        ),
    ) {
        itemsIndexed(subscriptions, key = { _, it -> it.url }) { index, subscription ->
            SourceRow(
                first = index == 0,
                last = index == subscriptions.lastIndex,
                subscription = subscription,
                onClick = { onOpen(subscription) },
            )
        }
    }
    }
}

@Composable
private fun SourceRow(
    first: Boolean,
    last: Boolean,
    subscription: Subscription,
    onClick: () -> Unit,
) {
    val ios = LocalAppStyle.current == AppStyle.IOS
    val shape = RoundedCornerShape(
        topStart = if (ios) (if (first) 24.dp else 0.dp) else if (first) 20.dp else 6.dp,
        topEnd = if (ios) (if (first) 24.dp else 0.dp) else if (first) 20.dp else 6.dp,
        bottomStart = if (ios) (if (last) 24.dp else 0.dp) else if (last) 20.dp else 6.dp,
        bottomEnd = if (ios) (if (last) 24.dp else 0.dp) else if (last) 20.dp else 6.dp,
    )
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (ios && !first) {
                    drawLine(
                        color = dividerColor,
                        start = Offset(16.dp.toPx(), 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 0.5.dp.toPx(),
                    )
                }
            },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIcon(
                AppIcons.Layers,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = subscription.title.ifBlank { hostOf(subscription.url) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tr("${subscription.nodeCount} серверов", "${subscription.nodeCount} servers"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (subscription.nodeCount == 0) {
                        AppIcon(
                            AppIcons.Danger,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            AppIcon(
                AppIcons.ChevronRight,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Экран подписки                                                      */
/* ------------------------------------------------------------------ */

@Composable
private fun SourceDetail(
    state: VeilState,
    subscription: Subscription,
    modifier: Modifier,
    onBack: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    val ios = LocalAppStyle.current == AppStyle.IOS
    val nodes = state.servers.filter { it.source == subscription.url }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (ios) {
                GlassIconButton(onClick = onBack) {
                    AppIcon(AppIcons.ArrowLeft, modifier = Modifier.size(20.dp))
                }
            } else {
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    AppIcon(AppIcons.ArrowLeft, modifier = Modifier.padding(10.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    subscription.title.ifBlank { hostOf(subscription.url) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("${nodes.size} серверов", "${nodes.size} servers"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ios) {
                GlassIconButton(onClick = { state.refreshSubscription(subscription) }) {
                    AppIcon(AppIcons.Refresh, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                }
            } else {
                Surface(
                    onClick = { state.refreshSubscription(subscription) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    AppIcon(AppIcons.Refresh, modifier = Modifier.padding(10.dp))
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "summary-title") { SectionLabel(tr("Подписка", "Subscription")) }
            item(key = "summary") {
                SegmentedGroup(
                    rows = buildList<@Composable () -> Unit> {
                        add { SegmentInfo(tr("Адрес", "Address"), maskUrl(subscription.url)) }
                        add { SegmentInfo(tr("Серверов", "Servers"), subscription.nodeCount.toString()) }
                        add {
                            SegmentInfo(
                                tr("Обновлена", "Updated"),
                                subscription.lastFetched.takeIf { it > 0 }?.let { formatAgo(it) } ?: tr("никогда", "never"),
                            )
                        }
                        add {
                            SegmentInfo(
                                tr("Истекает", "Expires"),
                                subscription.expireAt.takeIf { it > 0 }?.let { formatDate(it) } ?: tr("бессрочно", "none"),
                            )
                        }
                        add {
                            Column {
                                TrafficSummary(subscription)
                            }
                        }
                        if (subscription.webUrl.isNotBlank()) {
                            add { SegmentInfo(tr("Сайт", "Website"), subscription.webUrl) }
                        }
                        if (subscription.supportUrl.isNotBlank()) {
                            add { SegmentInfo(tr("Поддержка", "Support"), subscription.supportUrl) }
                        }
                    },
                )
            }

            item(key = "actions-title") { SectionLabel(tr("Действия", "Actions")) }
            item(key = "actions") {
                SegmentedGroup(
                    rows = listOf(
                        {
                            SegmentAction(
                                title = tr("Обновить сейчас", "Refresh now"),
                                subtitle = if (state.busy) tr("Загрузка…", "Loading…") else tr("Перечитать источник", "Re-read source"),
                                onClick = { state.refreshSubscription(subscription) },
                            )
                        },
                        {
                            val index = state.subscriptions.indexOfFirst { it.url == subscription.url }
                            SegmentAction(
                                title = tr("Выше", "Up"),
                                subtitle = tr("Поднять в списке источников", "Move up in sources"),
                                enabled = index > 0,
                                onClick = { state.moveSubscription(subscription.url, -1) },
                            )
                        },
                        {
                            val index = state.subscriptions.indexOfFirst { it.url == subscription.url }
                            SegmentAction(
                                title = tr("Ниже", "Down"),
                                subtitle = tr("Опустить в списке источников", "Move down in sources"),
                                enabled = index >= 0 && index < state.subscriptions.lastIndex,
                                onClick = { state.moveSubscription(subscription.url, 1) },
                            )
                        },
                        {
                            SegmentAction(
                                title = tr("Удалить подписку", "Delete subscription"),
                                subtitle = tr("Узлы этой подписки исчезнут из списка", "This subscription's servers will disappear"),
                                onClick = { confirmRemove = true },
                            )
                        },
                    ),
                )
            }

            item(key = "servers-title") { SectionLabel(tr("Серверы", "Servers")) }
            if (nodes.isEmpty()) {
                item(key = "no-nodes") {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            tr("В этой подписке нет серверов.", "This subscription has no servers."),
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
                            { SourceNodeRow(node) }
                        },
                    )
                }
            }

            item(key = "bottom-space") { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(tr("Удалить подписку?", "Delete subscription?"), fontWeight = FontWeight.Black) },
            text = { Text(tr("Узлы этой подписки исчезнут из списка. Это нельзя отменить.", "This subscription's servers will disappear. This cannot be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    state.removeSubscription(subscription)
                    onBack()
                }) { Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(tr("Отмена", "Cancel")) }
            },
        )
    }
}

@Composable
private fun SourceNodeRow(node: ProxyConfig) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            node.proto.wire.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (node.isUsable) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.error,
            modifier = Modifier.width(58.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                node.endpoint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TrafficSummary(subscription: Subscription) {
    val total = subscription.totalBytes
    val used = subscription.usedBytes.coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("Трафик", "Traffic"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (total > 0) "${formatBytes(used)} / ${formatBytes(total)}" else formatBytes(used),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (total > 0) {
            val fraction = (used.toFloat() / total).coerceIn(0f, 1f)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .padding(end = 0.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f), RoundedCornerShape(999.dp)),
                )
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceAtLeast(0.02f))
                        .height(4.dp)
                        .background(
                            if (fraction > 0.9f) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary,
                            RoundedCornerShape(999.dp),
                        ),
                )
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
        modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp),
    )
}

/** URL подписки — это credential, целиком в интерфейсе его показывать нельзя. */
private fun maskUrl(url: String): String {
    val tail = url.takeLast(4)
    return "${hostOf(url)}/…$tail"
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').ifBlank { url.take(24) }
