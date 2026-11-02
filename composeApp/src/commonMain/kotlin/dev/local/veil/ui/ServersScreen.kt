package dev.local.veil.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCode2
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.rememberLazyListState
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
    var onlyFast by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seen = remember { mutableSetOf<Any>() }
    // Действия над сервером и подпиской живут в нижних листах по долгому нажатию.
    var sheetServer by remember { mutableStateOf<ProxyConfig?>(null) }
    var sheetGroup by remember { mutableStateOf<Group?>(null) }

    val groups = remember(state.servers, state.subscriptions, onlyFast, state.pings) {
        val visible = if (onlyFast) {
            state.servers.filter { (state.pings[it.id] ?: Int.MAX_VALUE) < 250 }
        } else {
            state.servers
        }

        visible.groupBy { it.source }.map { (source, list) ->
            val subscription = state.subscriptions.firstOrNull { it.url == source }
            Group(
                key = source ?: "manual",
                title = when {
                    source == null -> "Вручную"
                    subscription?.title?.isNotBlank() == true -> subscription.title
                    else -> source.substringAfter("://").substringBefore('/')
                },
                source = source,
                servers = list,
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
                (fadeIn(tween(180)) + scaleIn(initialScale = 0.97f)) togetherWith
                    (fadeOut(tween(180)) + scaleOut(targetScale = 0.99f))
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        if (state.update != null) {
            item(key = "update") { UpdateAvailableRow(state = state) }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                FilterChip(
                    selected = !onlyFast,
                    onClick = { onlyFast = false },
                    label = { Text("Все (${state.servers.size})") },
                )
                FilterChip(
                    selected = onlyFast,
                    onClick = { onlyFast = true },
                    label = { Text("Быстрые") },
                )
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = if (state.pinging) state::cancelPing else state::pingAll,
                    enabled = state.servers.isNotEmpty(),
                ) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (state.pinging) "Стоп" else "Пинг")
                }
            }
        }

        if (groups.isEmpty()) {
            item {
                Text(
                    text = if (state.servers.isEmpty()) {
                        "Серверов пока нет.\nНажмите + и вставьте ссылку на подписку."
                    } else {
                        "Под фильтр ничего не подходит."
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
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .staggerIn(seen, "cta", 1)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Icon(Icons.Rounded.QrCode2, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.size(8.dp))
                        Text("Принять с телефона по QR", fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            groups.forEach { group ->
                item(key = "header-${group.key}") {
                    GroupHeader(
                        modifier = Modifier.animateItem(),
                        group = group,
                        expanded = group.key !in collapsed,
                        pinging = state.pinging,
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
                            modifier = Modifier.animateItem(),
                            server = server,
                            selected = server.id == state.selected?.id,
                            ping = state.pings[server.id],
                            measured = server.id in state.pings,
                            pinging = state.pinging,
                            onClick = { state.select(server.id, status) },
                            onLongClick = { sheetServer = server },
                        )
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    group: Group,
    expanded: Boolean,
    pinging: Boolean,
    onToggle: () -> Unit,
    onPing: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val headerShape = MaterialTheme.shapes.medium
    // Шеврон не подменяется, а поворачивается с пружиной.
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(180),
        label = "chevron",
    )
    Surface(
        shape = headerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .combinedClickable(onClick = onToggle, onLongClick = onMore)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(chevron),
            )
            Spacer(Modifier.size(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${group.servers.size} серверов",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onPing, enabled = !pinging) {
                Icon(Icons.Rounded.Speed, contentDescription = "Пинг группы", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "Действия", modifier = Modifier.size(20.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: ProxyConfig,
    selected: Boolean,
    ping: Int?,
    measured: Boolean,
    pinging: Boolean,
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

    // Выбранная строка округляется и перекрашивается с пружинным отскоком.
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
    val rowShape = RoundedCornerShape(corner)
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
                        "${server.proto.wire.uppercase()} · авто из ${server.balanced} транспортов"
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
            Icon(
                Icons.Rounded.SystemUpdate,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Доступно обновление ${release.version}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    if (progress != null) "Загрузка… ${(progress * 100).toInt()}%" else "Нажмите, чтобы скачать",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { state.dismissUpdate() }) {
                Icon(Icons.Rounded.Close, contentDescription = "Скрыть", modifier = Modifier.size(18.dp))
            }
        }
    }
}
