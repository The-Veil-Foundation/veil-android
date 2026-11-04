package dev.local.veil.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Button
import dev.local.veil.platform.PingMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.ProxyConfig
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.VpnStatus

/** Группа серверов: одна подписка или «вручную». */
private data class Group(
    val key: String,
    val title: String,
    val source: String?,
    val servers: List<ProxyConfig>,
    val meta: String? = null,
    val description: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    state: VeilState,
    status: VpnStatus,
    onOpenSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = 0L,
    onOpenReceive: () -> Unit = {},
) {
    var query by remember { mutableStateOf("") }
    var onlyFast by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seen = remember { mutableSetOf<Any>() }
    // Действия над сервером и подпиской живут в нижних листах по долгому нажатию.
    var sheetServer by remember { mutableStateOf<ProxyConfig?>(null) }
    var sheetGroup by remember { mutableStateOf<Group?>(null) }

    val groups = remember(state.servers, state.subscriptions, onlyFast, state.pings, query) {
        val needle = query.trim()
        val visible = state.visibleServers
            .filter { needle.isEmpty() || state.displayName(it).contains(needle, ignoreCase = true) }
            .filter { !onlyFast || (state.pings[it.id] ?: Int.MAX_VALUE) < 250 }

        visible.groupBy { it.source }.map { (source, list) ->
            val subscription = state.subscriptions.firstOrNull { it.url == source }
            val meta = subscription?.let { sub ->
                buildList {
                    if (sub.totalBytes > 0) {
                        add(tr("${formatBytes(sub.usedBytes)} из ${formatBytes(sub.totalBytes)}", "${formatBytes(sub.usedBytes)} of ${formatBytes(sub.totalBytes)}"))
                    }
                    if (sub.expireAt > 0) add(tr("до ${formatDate(sub.expireAt)}", "until ${formatDate(sub.expireAt)}"))
                }.joinToString(" · ").ifBlank { null }
            }
            Group(
                key = source ?: "manual",
                title = when {
                    source == null -> tr("Вручную", "Manual")
                    subscription?.title?.isNotBlank() == true -> subscription.title
                    else -> source.substringAfter("://").substringBefore('/')
                },
                source = source,
                servers = list,
                meta = meta,
                description = subscription?.description.orEmpty(),
            )
        }
    }

    val listState = rememberLazyListState()
    val compact by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 48
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Большая карточка плавно превращается в компактную панель с той же
        // кнопкой включения, как только список прокрутили.
        AnimatedContent(
            targetState = compact,
            transitionSpec = {
                if (state.isTv) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.97f)) togetherWith
                        (fadeOut(tween(180)) + scaleOut(targetScale = 0.99f))
                }
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            label = "status-morph",
        ) { isCompact ->
            if (isCompact) {
                CompactStatusBar(
                    status = status,
                    server = state.selected,
                    onToggle = { state.toggleTunnel(status) },
                    checkingServer = state.checkingServer,
                    now = now,
                )
            } else {
                StatusCard(
                    status = status,
                    server = state.selected,
                    onToggle = { state.toggleTunnel(status) },
                    checkingServer = state.checkingServer,
                    now = now,
                )
            }
        }

        RefreshableBox(
            enabled = !state.isTv,
            isRefreshing = state.busy,
            onRefresh = { state.refreshAll() },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
        if (state.update != null) {
            item(key = "update") { UpdateAvailableRow(state = state) }
        }
        if (!state.isTv) {
            item(key = "search") {
                SearchPill(query = query, onQueryChange = { query = it })
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                FilterChip(
                    selected = !onlyFast,
                    onClick = { onlyFast = false },
                    label = { Text(tr("Все (${state.servers.size})", "All (${state.servers.size})")) },
                )
                FilterChip(
                    selected = onlyFast,
                    onClick = { onlyFast = true },
                    label = { Text(tr("Быстрые", "Fastest")) },
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = if (state.pinging) state::cancelPing else state::pingAll,
                    enabled = state.servers.isNotEmpty(),
                ) {
                    AppIcon(AppIcons.Stopwatch, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (state.pinging) tr("Стоп", "Stop") else tr("Пинг", "Ping"))
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                Text(
                    text = if (state.servers.isEmpty()) {
                        tr("Серверов пока нет.\nНажмите + и вставьте ссылку на подписку.", "No servers yet.\nTap + and paste a subscription link.")
                    } else {
                        if (query.isNotBlank()) tr("Ничего не найдено.", "Nothing found.") else tr("Под фильтр ничего не подходит.", "Nothing matches the filter.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.staggerIn(seen, "empty", 0).padding(top = 40.dp, start = 24.dp, end = 24.dp),
                )
            }
            // На приставке камеры нет: подписки приезжают с телефона по QR.
            if (state.servers.isEmpty() && state.isTv) {
                item(key = "receive-cta") {
                    Button(
                        onClick = onOpenReceive,
                        shape = Md3Shapes.card,
                        modifier = Modifier
                            .staggerIn(seen, "cta", 1)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        AppIcon(AppIcons.Qr, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(tr("Принять с телефона по QR", "Receive from phone via QR"), fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            groups.forEach { group ->
                item(key = "header-${group.key}") {
                    GroupHeader(
                        modifier = if (state.isTv) Modifier else Modifier.animateItem(),
                        group = group,
                        expanded = group.key !in collapsed,
                        pinging = group.servers.any { it.id in state.pingingIds },
                        pingEnabled = !state.pinging,
                        onToggle = {
                            collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key
                        },
                        onPing = { state.pingSource(group.source) },
                        onMore = { sheetGroup = group },
                    )
                }

                if (group.key !in collapsed) {
                    itemsIndexed(group.servers, key = { _, it -> it.id }) { i, server ->
                        ServerRow(
                            modifier = if (state.isTv) Modifier else Modifier.animateItem(),
                            first = i == 0,
                            last = i == group.servers.lastIndex,
                            server = server,
                            selected = server.id == state.selected?.id,
                            ping = state.pings[server.id],
                            measured = server.id in state.pings,
                            pinging = server.id in state.pingingIds,
                            title = state.displayName(server),
                            onClick = { state.select(server.id, status) },
                            onLongClick = { sheetServer = server },
                        )
                    }
                }
            }
        }
        }
        }
    }

    sheetServer?.let { server ->
        ServerActionsSheet(
            state = state,
            status = status,
            server = server,
            onDismiss = { sheetServer = null },
        )
    }
    sheetGroup?.let { group ->
        SubscriptionActionsSheet(
            state = state,
            title = group.title,
            source = group.source,
            servers = group.servers,
            collapsed = group.key in collapsed,
            onToggleCollapse = {
                collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key
            },
            onDismiss = { sheetGroup = null },
        )
    }
}

/** Компактное поле поиска в стиле iOS: пилюля без рамки. */
@Composable
private fun SearchPill(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(
                AppIcons.Search,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            androidx.compose.foundation.text.BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            tr("Поиск серверов…", "Search servers…"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    inner()
                },
            )
            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(24.dp),
                ) {
                    AppIcon(
                        AppIcons.Close,
                        contentDescription = tr("Очистить", "Clear"),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    group: Group,
    expanded: Boolean,
    pinging: Boolean,
    pingEnabled: Boolean,
    onToggle: () -> Unit,
    onPing: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Шеврон не подменяется, а поворачивается — как в списках iOS.
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "chevron",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onToggle, onLongClick = onMore)
            .padding(start = 6.dp, end = 4.dp, top = 14.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(
            AppIcons.ChevronDown,
            modifier = Modifier.size(18.dp).rotate(chevron),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(6.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = "${group.servers.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            group.meta?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (group.description.isNotBlank()) {
                Text(
                    text = group.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        IconButton(onClick = onPing, enabled = pingEnabled) {
            if (pinging) {
                ExpressiveLoader(diameter = 18.dp)
            } else {
                AppIcon(
                    AppIcons.Stopwatch,
                    contentDescription = tr("Пинг группы", "Ping group"),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        IconButton(onClick = onMore) {
            AppIcon(AppIcons.More, contentDescription = tr("Действия", "Actions"), modifier = Modifier.size(20.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    first: Boolean,
    last: Boolean,
    server: ProxyConfig,
    selected: Boolean,
    ping: Int?,
    measured: Boolean,
    pinging: Boolean,
    title: String = server.name,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Экспрессивное нажатие: строка «проседает» под пальцем и пружинно
    // возвращается — как в Material 3 Expressive.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(180),
        label = "row-press",
    )

    // Сегментированный список как в bottom sheet: крайние строки крупнее.
    val corner by animateDpAsState(
        targetValue = if (selected) 32.dp else 24.dp,
        animationSpec = tween(180),
        label = "row-corner",
    )
    val rowColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        label = "row-bg",
    )
    val rowContent by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
        label = "row-fg",
    )
    val haptics = LocalHapticFeedback.current
    val topRadius = if (first) corner else Md3Shapes.xs
    val bottomRadius = if (last) corner else Md3Shapes.xs
    val rowShape = RoundedCornerShape(
        topStart = topRadius,
        topEnd = topRadius,
        bottomStart = bottomRadius,
        bottomEnd = bottomRadius,
    )
    Surface(
        shape = rowShape,
        color = rowColor,
        contentColor = rowContent,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(rowShape)
            // Короткое нажатие выбирает сервер, долгое открывает лист действий.
            .combinedClickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = server.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (server.balanced > 1) {
                        tr("${server.proto.wire.uppercase()} · авто из ${server.balanced} транспортов", "${server.proto.wire.uppercase()} · auto from ${server.balanced} transports")
                    } else {
                        "${server.proto.wire.uppercase()} · ${server.endpoint}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PingPill(ms = ping, measured = measured, pinging = pinging)
        }
    }
}

/** Небольшая плашка на главной: «доступно обновление». */
@Composable
private fun UpdateAvailableRow(state: VeilState) {
    val release = state.update ?: return
    val progress = state.updateProgress
    Surface(
        onClick = { if (progress == null) state.downloadUpdate() },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(
                AppIcons.Update,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    tr("Доступно обновление ${release.version}", "Update ${release.version} available"),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (progress != null) tr("Загрузка… ${(progress * 100).toInt()}%", "Downloading… ${(progress * 100).toInt()}%") else tr("Нажмите, чтобы скачать", "Tap to download"),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { state.dismissUpdate() }) {
                AppIcon(AppIcons.Close, contentDescription = tr("Скрыть", "Hide"), modifier = Modifier.size(18.dp))
            }
        }
    }
}
