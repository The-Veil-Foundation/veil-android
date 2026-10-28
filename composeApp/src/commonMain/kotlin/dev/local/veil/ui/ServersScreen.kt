package dev.local.veil.ui

import androidx.compose.animation.AnimatedVisibility
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
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    state: VeilState,
    status: VpnStatus,
    onOpenSubscriptions: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = 0L,
) {
    var onlyFast by remember { mutableStateOf(false) }
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }

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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            StatusCard(
                status = status,
                server = state.selected,
                onToggle = { state.toggleTunnel(status) },
                checkingServer = state.checkingServer,
                now = now,
            )
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
                TextButton(onClick = state::pingAll, enabled = !state.pinging && state.servers.isNotEmpty()) {
                    Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(4.dp))
                    Text(if (state.pinging) "Пинг…" else "Пинг")
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
                    modifier = Modifier.padding(top = 40.dp, start = 24.dp, end = 24.dp),
                )
            }
        } else {
            groups.forEach { group ->
                item(key = "header-${group.key}") {
                    GroupHeader(
                        group = group,
                        expanded = group.key !in collapsed,
                        busy = state.busy,
                        onToggle = {
                            collapsed = if (group.key in collapsed) collapsed - group.key else collapsed + group.key
                        },
                        onRefresh = {
                            group.source?.let { url ->
                                state.subscriptions.firstOrNull { it.url == url }?.let(state::refreshSubscription)
                            }
                        },
                        onOpenSubscriptions = onOpenSubscriptions,
                    )
                }

                if (group.key !in collapsed) {
                    items(group.servers, key = { it.id }) { server ->
                        ServerRow(
                            server = server,
                            selected = server.id == state.selected?.id,
                            ping = state.pings[server.id],
                            measured = server.id in state.pings,
                            pinging = state.pinging,
                            onClick = { state.select(server.id, status) },
                            onDelete = { state.removeServer(server.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupHeader(
    group: Group,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSubscriptions: () -> Unit,
) {
    val headerShape = MaterialTheme.shapes.medium
    Surface(
        shape = headerShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .clickable(onClick = onToggle)
                .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
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
            if (group.source != null) {
                IconButton(onClick = onRefresh, enabled = !busy) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Обновить подписку", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: ProxyConfig,
    selected: Boolean,
    ping: Int?,
    measured: Boolean,
    pinging: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    // Экспрессивное нажатие: строка «проседает» под пальцем и пружинно
    // возвращается — как в Material 3 Expressive.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = spring(dampingRatio = 0.42f, stiffness = 720f),
        label = "row-press",
    )

    val rowShape = if (selected) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = rowShape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
        else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
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
                    text = "${server.proto.wire.uppercase()} · ${server.endpoint}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PingPill(ms = ping, measured = measured, pinging = pinging)
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Удалить", modifier = Modifier.size(17.dp))
            }
        }
    }
}
