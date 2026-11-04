package dev.local.veil.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.TopAppBarState
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import dev.local.veil.platform.PlatformBackHandler
import dev.local.veil.platform.VpnPhase
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.veil.platform.AppContainer

private enum class Tab {
    SERVERS,
    SUBS,
    ROUTING,
    SETTINGS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val state = remember(container) { VeilState(container, scope) }
    val status by container.vpn.status.collectAsState()
    val darkTheme = when (state.settings.appearance) {
        dev.local.veil.core.Appearance.LIGHT -> false
        dev.local.veil.core.Appearance.DARK -> true
        dev.local.veil.core.Appearance.SYSTEM ->
            if (state.isTv) true else androidx.compose.foundation.isSystemInDarkTheme()
    }
    SystemBarsAppearance(darkTheme = darkTheme || state.settings.pitchBlack)

    val language = when (state.settings.language) {
        dev.local.veil.core.AppLanguage.SYSTEM -> systemLanguage()
        else -> state.settings.language
    }

    ProvideLanguage(language) {
    OrbitTheme(darkTheme = darkTheme, pitchBlack = state.settings.pitchBlack) {
    TvIndicationProvider(state.isTv) {

        var tab by remember { mutableStateOf(Tab.SERVERS) }
        var showSplit by remember { mutableStateOf(false) }
        var showAdd by remember { mutableStateOf(false) }
        var showQr by remember { mutableStateOf(false) }
        var showLogs by remember { mutableStateOf(false) }
        var showReceive by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            delay(900)
            state.autoConnectOnLaunch(status)
        }

        // Единый тикер на всё приложение: таймер в карточке не зависит от того,
        // пересоздалась ли карточка при прокрутке или возврате в приложение.
        var now by remember { mutableStateOf(currentTimeMillis()) }
        if (!state.isTv) {
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000)
                    now = currentTimeMillis()
                }
            }
        }

        // Жест «назад» сначала закрывает шторку/вложенный экран и возвращает
        // на «Серверы», и только потом выпускает из приложения.
        PlatformBackHandler(enabled = showQr || showAdd || showLogs || showSplit || showReceive || tab != Tab.SERVERS) {
            when {
                showQr -> showQr = false
                showAdd -> showAdd = false
                showLogs -> showLogs = false
                showSplit -> showSplit = false
                showReceive -> showReceive = false
                else -> tab = Tab.SERVERS
            }
        }

        // Ошибка ядра показывается буллетином и не висит в списке.
        LaunchedEffect(status.message, status.phase) {
            val message = status.message
            if (status.phase == VpnPhase.ERROR && !message.isNullOrBlank()) {
                state.showBulletin(message, BulletinKind.ERROR)
            }
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

        if (!state.isTv) {
            LaunchedEffect(Unit) {
                state.refreshCommunityDue()
            }

            LaunchedEffect(Unit) {
                val hours = state.settings.autoRefreshHours
                if (hours > 0) {
                    val refreshNow = currentTimeMillis()
                    state.subscriptions
                        .filter { refreshNow - it.lastFetched > hours * 3_600_000L }
                        .forEach { state.refreshSubscription(it) }
                }
            }
        }

        // Буллетин мягко съезжает, когда нижняя навигация прячется.
        val bulletinBottom by animateDpAsState(
            targetValue = if (state.isTv || showSplit || showLogs || showReceive) 24.dp else 104.dp,
            animationSpec = tween(180),
            label = "bulletin-pad",
        )

        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (state.isTv) Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                    else Modifier,
                ),
        ) {
            Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(if (state.isTv) Modifier.padding(start = 88.dp) else Modifier)
                .then(
                    if (!state.isTv) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                    else Modifier,
                ),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
              // Панели сменяют друг друга коротким слайдом без отскока.
              AnimatedContent(
                targetState = if (showLogs) 100 else if (showSplit) 101 else if (showReceive) 102 else tab.ordinal,
                transitionSpec = {
                    if (state.isTv) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (fadeIn(tween(180)) + slideInVertically(tween(200)) { -it / 4 }) togetherWith
                            fadeOut(tween(140))
                    }
                },
                label = "topbar",
              ) { bar ->
                if (bar == 100) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showLogs = false }) {
                                AppIcon(AppIcons.ArrowLeft, contentDescription = tr("Назад", "Back"))
                            }
                        },
                        title = { Text(tr("Логи ядра", "Core logs"), fontWeight = FontWeight.Black) },
                    )
                } else if (bar == 101) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showSplit = false }) {
                                AppIcon(AppIcons.ArrowLeft, contentDescription = tr("Назад", "Back"))
                            }
                        },
                        title = { Text(tr("Раздельный туннель", "Split tunneling"), fontWeight = FontWeight.Black) },
                    )
                } else if (bar == 102) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showReceive = false }) {
                                AppIcon(AppIcons.ArrowLeft, contentDescription = tr("Назад", "Back"))
                            }
                        },
                        title = { Text(tr("Принять с другого устройства", "Receive from another device"), fontWeight = FontWeight.Black) },
                    )
                } else if (bar == Tab.SERVERS.ordinal) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        scrollBehavior = scrollBehavior,
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VeilMark(34.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Veil", fontWeight = FontWeight.Black)
                            }
                        },
                    )
                } else {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VeilMark(30.dp)
                                Spacer(Modifier.size(10.dp))
                                Text(
                                    text = when (Tab.entries[bar]) {
                                        Tab.SUBS -> tr("Источники", "Sources")
                                        Tab.ROUTING -> tr("Маршрутизация", "Routing")
                                        else -> tr("Настройки", "Settings")
                                    },
                                    fontWeight = FontWeight.Black,
                                )
                            }
                        },
                    )
                }
              }
            },
            bottomBar = {
                if (!state.isTv) {
                AnimatedVisibility(
                    visible = !showSplit && !showLogs && !showReceive,
                    enter = slideInVertically(tween(180)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150)),
                ) {
                    VeilNavigationBar(current = tab, onSelect = { tab = it })
                }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !showSplit && !showReceive && (tab == Tab.SERVERS || tab == Tab.SUBS),
                    enter = scaleIn(tween(180)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    // Кнопка морфится в цветок при нажатии.
                    val fabSource = remember { MutableInteractionSource() }
                    val fabPressed by fabSource.collectIsPressedAsState()
                    val fabShape = rememberMorphShape(
                        active = fabPressed,
                        to = MaterialShapePresets.Flower,
                    )
                    FloatingActionButton(
                        onClick = { showAdd = true },
                        shape = fabShape,
                        interactionSource = fabSource,
                    ) {
                        AppIcon(AppIcons.Add, contentDescription = tr("Добавить", "Add"))
                    }
                }
            },
        ) { insets ->
            val modifier = Modifier.padding(insets)

            // Логи и раздельный туннель выезжают справа, основной экран отъезжает назад.
            AnimatedContent(
                targetState = if (showLogs) 2 else if (showSplit) 1 else if (showReceive) 3 else 0,
                transitionSpec = {
                    if (state.isTv) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else if (targetState > initialState) {
                        (slideInHorizontally(tween(180)) { it } + fadeIn(tween(180))) togetherWith
                            (slideOutHorizontally(tween(180)) { -it / 4 } + fadeOut(tween(180)) + scaleOut(targetScale = 0.94f))
                    } else {
                        (slideInHorizontally(tween(180)) { -it / 4 } + fadeIn(tween(180)) + scaleIn(initialScale = 0.94f)) togetherWith
                            (slideOutHorizontally(tween(180)) { it } + fadeOut(tween(180)))
                    }
                },
                label = "overlay",
            ) { overlay ->
            if (overlay == 2) {
                LogsScreen(
                    provider = container.logs,
                    modifier = modifier.fillMaxSize(),
                    onBack = { showLogs = false },
                    onBulletin = { state.showBulletin(it) },
                )
            } else if (overlay == 1) {
                SplitTunnelScreen(state = state, modifier = modifier.fillMaxSize())
            } else if (overlay == 3) {
                ReceiveScreen(state = state, modifier = modifier.fillMaxSize())
            } else {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        if (state.isTv) {
                            return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
                        }
                        // Соседние вкладки: короткий слайд без отскока.
                        val forward = targetState.ordinal > initialState.ordinal
                        val shift = { full: Int -> if (forward) full / 7 else -full / 7 }
                        (slideInHorizontally(
                            animationSpec = tween(220),
                            initialOffsetX = shift,
                        ) + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(180),
                                targetOffsetX = { full -> -shift(full) },
                            ) + fadeOut(tween(180)))
                    },
                    label = "tab",
                ) { current ->
                    when (current) {
                        Tab.SERVERS -> ServersScreen(
                            state = state,
                            status = status,
                            onOpenSubscriptions = { tab = Tab.SUBS },
                            modifier = modifier,
                            now = now,
                            onOpenReceive = { showReceive = true },
                        )
                        Tab.SUBS -> SubscriptionsScreen(state = state, modifier = modifier.fillMaxSize())
                        Tab.ROUTING -> RoutingScreen(state = state, modifier = modifier)
                        Tab.SETTINGS -> SettingsScreen(
                            state = state,
                            status = status,
                            onOpenSplit = { showSplit = true },
                            onOpenSubscriptions = { tab = Tab.SUBS },
                            onOpenLogs = { showLogs = true },
                            onOpenReceive = { showReceive = true },
                            modifier = modifier,
                        )
                    }
                }
            }
            }
        }

            if (state.isTv) {
                TvNavigationRail(
                    current = tab,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(72.dp),
                )
            }

            BulletinHost(
                bulletin = state.bulletin,
                onDismiss = state::dismissBulletin,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = bulletinBottom),
            )
        }

        // QR приставки в локальной сети: спрашиваем, прежде чем отправлять ключи.
        if (state.pendingPairing != null) {
            AlertDialog(
                onDismissRequest = state::dismissTransfer,
                shape = RoundedCornerShape(32.dp),
                icon = { AppIcon(AppIcons.Qr) },
                title = { Text(tr("Отправить на приставку?", "Send to the TV box?"), fontWeight = FontWeight.Black) },
                text = {
                    Text(
                        tr("Подписок: ${state.subscriptions.size}, серверов вручную: ${state.servers.count { it.source == null }}. ", "Subscriptions: ${state.subscriptions.size}, manual servers: ${state.servers.count { it.source == null }}. ") +
                            tr("Они уйдут на устройство в вашей локальной сети.", "They will be sent to a device on your local network."),
                    )
                },
                confirmButton = { TextButton(onClick = state::confirmTransfer) { Text(tr("Отправить", "Send")) } },
                dismissButton = { TextButton(onClick = state::dismissTransfer) { Text(tr("Отмена", "Cancel")) } },
            )
        }

        if (showQr) {
            QrScannerDialog(
                onResult = { scanned ->
                    showQr = false
                    showAdd = false
                    state.addInput(scanned)
                },
                onDismiss = { showQr = false },
            )
        }

        val addSheet: @Composable () -> Unit = {
            AddSheet(
                busy = state.busy,
                onAdd = { text ->
                    state.addInput(text)
                    showAdd = false
                },
                onCancel = { showAdd = false },
                // На приставке камеры нет — там вместо сканера приём по QR.
                onScan = if (qrSupported && !state.isTv) {
                    { showQr = true }
                } else {
                    null
                },
                onReceive = {
                    showAdd = false
                    showReceive = true
                },
            )
        }

        if (showAdd) {
            if (state.isTv) {
                Dialog(onDismissRequest = { showAdd = false }) {
                    Surface(
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) { addSheet() }
                }
            } else {
                ModalBottomSheet(
                    onDismissRequest = { showAdd = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                ) { addSheet() }
            }
        }
    }
    }
    }
}

@Composable
private fun AddSheet(
    busy: Boolean,
    onAdd: (String) -> Unit,
    onCancel: () -> Unit,
    onScan: (() -> Unit)? = null,
    onReceive: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    val tv = LocalTvMode.current
    val textFocus = remember { FocusRequester() }
    LaunchedEffect(tv) {
        if (tv) {
            delay(400)
            runCatching { textFocus.requestFocus() }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(tr("Добавить", "Add"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .focusRequester(textFocus),
            shape = RoundedCornerShape(20.dp),
            label = { Text(tr("Ссылка на подписку или share-ссылки", "Subscription link or share links")) },
            placeholder = { Text("https://subs.example.com/…\n\nvless://…\nvmess://…\nss://…") },
        )

        RowButtons(busy = busy, enabled = text.isNotBlank(), onAdd = { onAdd(text) }, onCancel = onCancel)

        if (onScan != null) {
            OutlinedButton(
                onClick = onScan,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppIcon(AppIcons.Qr, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(tr("Сканировать QR-код", "Scan QR code"))
            }
        }

        if (onReceive != null) {
            OutlinedButton(
                onClick = onReceive,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                AppIcon(AppIcons.Qr, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(tr("Принять с другого устройства", "Receive from another device"))
            }
        }

        Text(
            text = tr("Ссылка, base64 или конфиг Xray.", "Link, base64 or Xray config."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RowButtons(
    busy: Boolean,
    enabled: Boolean,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onAdd,
            enabled = enabled && !busy,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.weight(1f),
        ) { Text(if (busy) tr("Загружаю…", "Loading…") else tr("Добавить", "Add"), fontWeight = FontWeight.Bold) }
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(tr("Отмена", "Cancel")) }
    }
}



/** Нижняя навигация: Solar-иконки, выбранная чуть крупнее — без подпрыгивания. */
@Composable
private fun VeilNavigationBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Tab.entries.forEach { entry ->
            NavigationBarItem(
                selected = current == entry,
                onClick = { onSelect(entry) },
                icon = { TabIcon(entry = entry, selected = current == entry) },
                label = {
                    Text(
                        when (entry) {
                            Tab.SERVERS -> tr("Серверы", "Servers")
                            Tab.SUBS -> tr("Источники", "Sources")
                            Tab.ROUTING -> tr("Маршрут", "Routing")
                            Tab.SETTINGS -> tr("Настройки", "Settings")
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun TabIcon(entry: Tab, selected: Boolean) {
    val pop by animateFloatAsState(
        if (selected) 1.1f else 1f,
        tween(180),
        label = "nav-pop",
    )
    AppIcon(
        icon = when (entry) {
            Tab.SERVERS -> AppIcons.Server
            Tab.SUBS -> AppIcons.Layers
            Tab.ROUTING -> AppIcons.Routing
            Tab.SETTINGS -> AppIcons.Settings
        },
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer { scaleX = pop; scaleY = pop },
    )
}


/** Боковая навигация для телевизора: пульт ходит по ней без «прыжков» вниз. */
@Composable
private fun TvNavigationRail(
    current: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    // На телевизоре первый кадр не сразу принимает фокус: пробуем несколько раз,
    // иначе пульт «застревает» на поле поиска или вообще ни на чём.
    LaunchedEffect(current) {
        repeat(12) {
            delay(180)
            val ok = runCatching { requesters.getValue(current).requestFocus() }.isSuccess
            if (ok) return@LaunchedEffect
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Tab.entries.forEach { entry ->
                NavigationRailItem(
                    selected = current == entry,
                    onClick = { onSelect(entry) },
                    icon = { TabIcon(entry = entry, selected = current == entry) },
                    label = {
                        Text(
                            text = when (entry) {
                                Tab.SERVERS -> tr("Серверы", "Servers")
                                Tab.SUBS -> tr("Источники", "Sources")
                                Tab.ROUTING -> tr("Маршрут", "Routing")
                                Tab.SETTINGS -> tr("Настройки", "Settings")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    alwaysShowLabel = true,
                    modifier = Modifier.focusRequester(requesters.getValue(entry)),
                )
            }
        }
    }
}
