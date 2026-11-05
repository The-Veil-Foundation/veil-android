package dev.local.veil.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import dev.local.veil.platform.PingMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.focus.focusRequester
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
    val subscription: dev.local.veil.core.Subscription? = null,
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
    onAdd: () -> Unit = {},
    query: String = "",
) {
    // Пустой список — отдельный экран: карточка подключения, поиск, фильтры и
    // пинг без единого узла только мешают и выглядят сломанными.
    if (state.servers.isEmpty() && !state.isTv) {
        EmptyServers(onAdd = onAdd, onOpenReceive = onOpenReceive, modifier = modifier)
        return
    }
    // «Сначала быстрые» — сортировка по последнему замеру. Пока идёт пинг,
    // порядок не трогаем: строки не прыгают под пальцем.
    var byPing by remember { mutableStateOf(false) }
    val sortPings = if (byPing && !state.pinging) state.pings else null
    val reduceMotion = LocalReduceMotion.current
    var collapsed by remember { mutableStateOf<Set<String>>(emptySet()) }
    val seen = remember { mutableSetOf<Any>() }
    // Действия над сервером и подпиской живут в нижних листах по долгому нажатию.
    var sheetServer by remember { mutableStateOf<ProxyConfig?>(null) }
    var sheetGroup by remember { mutableStateOf<Group?>(null) }

    val groups = remember(state.servers, state.subscriptions, state.settings.annotations, sortPings, query) {
        val needle = query.trim()
        val visible = state.visibleServers
            .filter { server ->
                needle.isEmpty() ||
                    state.displayName(server).contains(needle, ignoreCase = true) ||
                    // Поиск по адресу/порту: удобно и для проверки узла.
                    server.endpoint.contains(needle, ignoreCase = true)
            }
            .let { list ->
                if (sortPings == null) list
                // Не ответившие и не измеренные — в конец, порядок среди них прежний.
                else list.sortedBy { sortPings[it.id] ?: Int.MAX_VALUE }
            }

        // Группы идут в порядке источников (закреплённые сверху), «Вручную» — в конце.
        val order = state.orderedSubscriptions.mapIndexed { index, sub -> sub.url to index }.toMap()
        visible.groupBy { it.source }.entries
            .sortedBy { (source, _) -> source?.let { order[it] } ?: Int.MAX_VALUE }
            .map { (source, list) ->
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
                    subscription != null -> subscription.displayName
                    else -> source.substringAfter("://").substringBefore('/')
                },
                source = source,
                servers = list,
                meta = meta,
                description = subscription?.description.orEmpty(),
                subscription = subscription,
            )
        }
    }

    val listState = rememberLazyListState()
    // Компактная панель появляется поверх списка, когда большая карточка
    // уехала за край: высота списка не меняется, прокрутка не дёргается.
    val compact by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 }
    }

    // Список серверов выносим в отдельный блок: на телефоне он под карточкой,
    // на широком экране (ландшафт, планшет, ТВ) — справа от неё.
    val serverList: @Composable (Modifier, Boolean) -> Unit = { listModifier, withStatus ->
        RefreshableBox(
            enabled = !state.isTv,
            isRefreshing = state.busy,
            onRefresh = { state.refreshAll() },
            modifier = listModifier,
        ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
        if (withStatus) {
            item(key = "status") {
                StatusCard(
                    status = status,
                    server = state.selected,
                    onToggle = { state.toggleTunnel(status) },
                    checkingServer = state.checkingServer,
                    now = now,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (state.update != null) {
            item(key = "update") { UpdateAvailableRow(state = state) }
        }
        item {
            val chipShape = CircleShape
            val chipColors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Text(
                    tr("Серверов: ${state.visibleServers.size}", "Servers: ${state.visibleServers.size}"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
                FilterChip(
                    selected = byPing,
                    onClick = {
                        byPing = !byPing
                        // Сортировать нечего, пока ничего не измерено: меряем сразу.
                        if (byPing && state.pings.isEmpty() && !state.pinging) state.pingAll()
                    },
                    shape = chipShape,
                    colors = chipColors,
                    leadingIcon = if (byPing) {
                        { AppIcon(AppIcons.Check, modifier = Modifier.size(16.dp)) }
                    } else null,
                    label = { Text(tr("Сначала быстрые", "Fastest first")) },
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
            groups.forEachIndexed { groupIndex, group ->
                val expanded = group.key !in collapsed
                // Карточка подписки — верхний сегмент своей группы серверов.
                val merged = expanded
                if (groupIndex > 0) {
                    item(key = "gap-${group.key}") { Spacer(Modifier.height(12.dp)) }
                }
                item(key = "header-${group.key}") {
                    GroupHeader(
                        modifier = if (state.isTv || reduceMotion) Modifier else Modifier.animateItem(),
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
                            modifier = if (state.isTv || reduceMotion) Modifier else Modifier.animateItem(),
                            first = i == 0 && !merged,
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

    // Адаптив: в узком окне карточка сверху, в широком (ландшафт, планшет,
    // Android TV) — слева, а список серверов справа от неё.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val availableWidth = maxWidth
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(minOf(400.dp, availableWidth * 0.40f))
                        .fillMaxHeight()
                        // В ландшафте высота маленькая: если карточка не влезает,
                        // она прокручивается, а не сжимает кнопку подключения.
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    StatusCard(
                        status = status,
                        server = state.selected,
                        onToggle = { state.toggleTunnel(status) },
                        checkingServer = state.checkingServer,
                        now = now,
                    )
                }
                serverList(Modifier.weight(1f).fillMaxHeight(), false)
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                serverList(Modifier.fillMaxSize(), true)
                androidx.compose.animation.AnimatedVisibility(
                    visible = compact,
                    enter = androidx.compose.animation.slideInVertically(tween(180)) { -it } + fadeIn(tween(150)),
                    exit = androidx.compose.animation.slideOutVertically(tween(150)) { -it } + fadeOut(tween(120)),
                    // Подложка цвета фона: строки списка не просвечивают над полосой.
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    CompactStatusBar(
                        status = status,
                        server = state.selected,
                        onToggle = { state.toggleTunnel(status) },
                        checkingServer = state.checkingServer,
                        now = now,
                    )
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

/** Первый запуск: ни одного узла. Одна понятная точка входа вместо пустого списка. */
@Composable
private fun EmptyServers(onAdd: () -> Unit, onOpenReceive: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp)
            .padding(bottom = 96.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(88.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AppIcon(AppIcons.Server, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            tr("Серверов пока нет", "No servers yet"),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            tr(
                "Добавьте ссылку на подписку или отдельный узел — они появятся здесь.",
                "Add a subscription link or a single server and it will show up here.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(
            onClick = onAdd,
            shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            AppIcon(AppIcons.Add, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(8.dp))
            Text(tr("Добавить подписку", "Add subscription"), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onOpenReceive, modifier = Modifier.fillMaxWidth().height(48.dp)) {
            AppIcon(AppIcons.Qr, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(tr("Принять с другого устройства", "Receive from another device"))
        }
    }
}

/** Компактное поле поиска в стиле iOS: пилюля без рамки. */
@Composable
internal fun SearchPill(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier, autoFocus: Boolean = false) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) {
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
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
                modifier = Modifier.weight(1f).focusRequester(focus),
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
        animationSpec = VeilMotion.fastSpatial,
        label = "chevron",
    )
    run {
        SubscriptionCard(
            group = group,
            subscription = group.subscription,
            expanded = expanded,
            chevron = chevron,
            pinging = pinging,
            pingEnabled = pingEnabled,
            onToggle = onToggle,
            onPing = onPing,
            onMore = onMore,
            modifier = modifier,
        )
        return
    }
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

/**
 * Шапка подписки над её серверами: объявление панели целиком, трафик с
 * полосой (безлимит — полная полоса) и срок с остатком дней.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SubscriptionCard(
    group: Group,
    subscription: dev.local.veil.core.Subscription?,
    expanded: Boolean,
    chevron: Float,
    pinging: Boolean,
    pingEnabled: Boolean,
    onToggle: () -> Unit,
    onPing: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val used = subscription?.usedBytes?.coerceAtLeast(0) ?: 0L
    val total = subscription?.totalBytes ?: 0L
    val unlimited = total <= 0
    val fraction = if (unlimited) 1f else (used.toFloat() / total).coerceIn(0f, 1f)
    val expire = subscription?.expireAt ?: 0L
    val secondsLeft = if (expire > 0) expire - currentTimeMillis() / 1000 else Long.MAX_VALUE
    val expiring = secondsLeft < 3 * 86400
    val trafficLow = !unlimited && fraction > 0.9f
    var showAll by remember { mutableStateOf(false) }

    // Развёрнутая группа: карточка — верхний сегмент, серверы продолжают её.
    val bottom by androidx.compose.animation.core.animateDpAsState(
        if (expanded) Md3Shapes.xs else 28.dp,
        animationSpec = tween(180),
        label = "card-bottom",
    )
    Surface(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = bottom, bottomEnd = bottom),
        color = scheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(bottom = 2.dp),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = if (subscription != null) 16.dp else 6.dp)) {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = onToggle, onLongClick = onMore),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(
                        group.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        tr("Серверов: ${group.servers.size}", "Servers: ${group.servers.size}") +
                            (subscription?.lastFetched?.takeIf { it > 0 }?.let { " · " + formatAgo(it) } ?: ""),
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                // Пинг группы из одного сервера — то же, что пинг строки: кнопка лишняя.
                if (group.servers.size > 1) {
                    IconButton(onClick = onPing, enabled = pingEnabled) {
                        if (pinging) {
                            ExpressiveLoader(diameter = 18.dp)
                        } else {
                            AppIcon(AppIcons.Stopwatch, contentDescription = tr("Пинг группы", "Ping group"), modifier = Modifier.size(20.dp))
                        }
                    }
                }
                // У «Вручную» нет ни ссылки, ни обновления — меню было бы пустым.
                if (subscription != null) {
                    IconButton(onClick = onMore) {
                        AppIcon(AppIcons.More, contentDescription = tr("Действия", "Actions"), modifier = Modifier.size(20.dp))
                    }
                }
                IconButton(onClick = onToggle) {
                    AppIcon(
                        AppIcons.ChevronDown,
                        contentDescription = tr("Свернуть", "Collapse"),
                        modifier = Modifier.size(20.dp).rotate(chevron),
                    )
                }
            }

            if (subscription != null) {
                if (subscription.description.isNotBlank()) {
                    Text(
                        subscription.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = if (showAll) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(end = 12.dp, top = 2.dp, bottom = 10.dp)
                            .combinedClickable(onClick = { showAll = !showAll }),
                    )
                }

                Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Потрачено", "Used"), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                        Text(
                            if (unlimited) formatBytes(used) else tr("${formatBytes(used)} из ${formatBytes(total)}", "${formatBytes(used)} of ${formatBytes(total)}"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (trafficLow) scheme.error else scheme.onSurface,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (expire > 0) tr("до ${formatDate(expire)}", "until ${formatDate(expire)}") else tr("Срок", "Expiry"),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            if (expire > 0) formatRemaining(expire) else tr("бессрочно", "no expiry"),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (expiring) scheme.error else scheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.size(10.dp))
                Box(
                    Modifier
                        .padding(end = 12.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.14f), CircleShape),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceAtLeast(0.03f))
                            .height(8.dp)
                            .background(
                                when {
                                    trafficLow -> scheme.error
                                    unlimited -> scheme.primary.copy(alpha = 0.55f)
                                    else -> scheme.primary
                                },
                                CircleShape,
                            ),
                    )
                }
                Text(
                    when {
                        unlimited -> tr("Безлимитный трафик", "Unlimited traffic")
                        else -> tr("Осталось ${formatBytes((total - used).coerceAtLeast(0))}", "${formatBytes((total - used).coerceAtLeast(0))} left")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
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
        animationSpec = VeilMotion.fastSpatial,
        label = "row-press",
    )

    // Сегментированный список как в bottom sheet: крайние строки крупнее.
    val corner by animateDpAsState(
        targetValue = if (selected) 32.dp else 24.dp,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 1400f),
        label = "row-corner",
    )
    val rowColor by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = spring(dampingRatio = 1f, stiffness = 1600f),
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
        shape = MaterialTheme.shapes.large,
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
