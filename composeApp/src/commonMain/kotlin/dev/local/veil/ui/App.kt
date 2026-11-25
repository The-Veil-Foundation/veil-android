package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.material3.Text
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.local.veil.platform.AppContainer
import dev.local.veil.platform.PlatformBackHandler
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.isLowRamDevice
import kotlinx.coroutines.delay

private enum class Tab {
    SERVERS,
    SUBS,
    ROUTING,
    SHARING,
    SETTINGS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val state = remember(container) { VeilState(container, scope) }
    val status by container.vpn.status.collectAsState()
    val foreground by container.vpn.foreground.collectAsState()

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (state.settings.appearance) {
        dev.local.veil.core.Appearance.LIGHT -> false
        dev.local.veil.core.Appearance.DARK -> true
        dev.local.veil.core.Appearance.SYSTEM -> state.isTv || systemDark
    }

    var tab by remember { mutableStateOf(Tab.SERVERS) }

    val settingsScroll = rememberScrollState()
    val routingScroll = rememberScrollState()

    val tabs = Tab.entries.filter { candidate ->
        (candidate != Tab.SUBS || state.settings.showSourcesTab) &&
            (candidate != Tab.SHARING || (state.sharing != null && state.settings.showSharingTab))
    }

    val incoming by dev.local.veil.core.IncomingLinks.link.collectAsState()
    LaunchedEffect(incoming) {
        dev.local.veil.core.IncomingLinks.take()?.let { state.addInput(it) }
    }
    var showSplit by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }

    var serverQuery by remember { mutableStateOf<String?>(null) }
    var showQr by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var showReceive by remember { mutableStateOf(false) }

    val language = when (state.settings.language) {
        dev.local.veil.core.AppLanguage.SYSTEM -> systemLanguage()
        else -> state.settings.language
    }

    ProvideLanguage(language) {
    OrbitTheme(darkTheme = darkTheme, pitchBlack = state.settings.pitchBlack) {

    SystemBarsAppearance(
        darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f,
    )
    TvIndicationProvider(state.isTv) {
        val reduceMotion = isLowRamDevice()
        CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {

        LaunchedEffect(Unit) {
            delay(900)
            state.autoConnectOnLaunch(status)
        }
        LaunchedEffect(status.phase) {
            dev.local.veil.core.AppLog.event("VPN state: ${status.phase.name}")
        }

        var now by remember { mutableStateOf(currentTimeMillis()) }
        val ticking = foreground &&
            (status.isConnected || status.phase == VpnPhase.CONNECTING)
        if (!state.isTv && ticking) {
            LaunchedEffect(ticking) {
                now = currentTimeMillis()
                while (true) {
                    delay(if (reduceMotion) 2000 else 1000)
                    now = currentTimeMillis()
                }
            }
        }

        PlatformBackHandler(enabled = showQr || showAdd || serverQuery != null) {
            when {
                showQr -> showQr = false
                serverQuery != null && tab == Tab.SERVERS -> serverQuery = null
                showAdd -> showAdd = false
                showLogs -> showLogs = false
                showSplit -> showSplit = false
                showReceive -> showReceive = false
                else -> tab = Tab.SERVERS
            }
        }

        LaunchedEffect(status.message, status.phase) {
            val message = status.message
            if (status.phase == VpnPhase.ERROR && !message.isNullOrBlank()) {
                state.showBulletin(message, BulletinKind.ERROR)
            }
        }

        LaunchedEffect(Unit) {
            state.ensureGeo()
        }

        if (!state.isTv) {
            LaunchedEffect(Unit) {
                state.refreshCommunityDue()
            }

            LaunchedEffect(Unit) {
                val hours = state.settings.autoRefreshHours
                if (hours > 0) {
                    val refreshNow = currentTimeMillis()
                    state.subscriptions
                        .filter { it.autoUpdate && refreshNow - it.lastFetched > hours * 3_600_000L }
                        .forEach { state.refreshSubscription(it) }
                }
            }
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .then(
                    if (state.isTv) Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                    else Modifier,
                ),
        ) {

            val landscape = !state.isTv && maxWidth > maxHeight

            val cutout = WindowInsets.safeDrawing
            val bars = WindowInsets.systemBars
            val density = LocalDensity.current
            val layoutDirection = LocalLayoutDirection.current
            val cutoutStart = with(density) {
                (cutout.getLeft(this, layoutDirection) - bars.getLeft(this, layoutDirection))
                    .coerceAtLeast(0)
                    .toDp()
            }
            val cutoutEnd = with(density) {
                (cutout.getRight(this, layoutDirection) - bars.getRight(this, layoutDirection))
                    .coerceAtLeast(0)
                    .toDp()
            }
            Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        state.isTv -> Modifier.padding(start = 88.dp)
                        landscape -> Modifier.padding(start = 78.dp + cutoutStart, end = cutoutEnd)
                        else -> Modifier
                    },
                ),
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                BulletinHost(bulletin = state.bulletin, onDismiss = state::dismissBulletin)
            },
            topBar = {

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
                        title = { Text(tr("Логи приложения и ядра", "App and core logs"), fontWeight = FontWeight.Black) },
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
                        title = {
                            AnimatedVisibility(
                                visible = serverQuery != null,
                                enter = if (reduceMotion || state.isTv) EnterTransition.None else expandHorizontally(tween(240), expandFrom = Alignment.End) + fadeIn(tween(180)),
                                exit = if (reduceMotion || state.isTv) ExitTransition.None else shrinkHorizontally(tween(200), shrinkTowards = Alignment.End) + fadeOut(tween(140)),
                            ) {
                                 SearchPill(
                                     query = serverQuery.orEmpty(),
                                    onQueryChange = { serverQuery = it },
                                    autoFocus = true,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                             }
                        },
                        actions = {
                            if (!state.isTv && state.servers.isNotEmpty()) {
                                if (serverQuery != null) {
                                    IconButton(onClick = { serverQuery = null }) {
                                        AppIcon(AppIcons.Close, contentDescription = tr("Закрыть поиск", "Close search"))
                                    }
                                } else {
                                    IconButton(onClick = { serverQuery = "" }) {
                                        AppIcon(AppIcons.Search, contentDescription = tr("Поиск", "Search"))
                                    }
                                }
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
                if (!state.isTv && !landscape) {
                AnimatedVisibility(
                    visible = !showSplit && !showLogs && !showReceive,
                    enter = slideInVertically(tween(180)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150)),
                ) {
                    VeilNavigationBar(
                        current = tab,
                        tabs = tabs,
                        activeLabelOnly = state.settings.dockActiveLabelOnly,
                        onSelect = { tab = it },
                    )
                }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !showSplit && !showReceive && ((tab == Tab.SERVERS && state.servers.isNotEmpty()) || (tab == Tab.SUBS && (state.servers.isNotEmpty() || state.subscriptions.isNotEmpty()))),
                    enter = scaleIn(tween(180)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {

                    FloatingActionButton(
                        onClick = { showAdd = true },
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        AppIcon(AppIcons.Add, contentDescription = tr("Добавить", "Add"))
                    }
                }
            },
        ) { insets ->
            val modifier = Modifier.padding(insets)

            PredictiveNavigation(
                page = if (showLogs) 2 else if (showSplit) 1 else if (showReceive) 3 else 0,
                previous = if (showLogs || showSplit || showReceive) 0 else null,
                onBack = { showLogs = false; showSplit = false; showReceive = false },
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
                TabNavigation(
                    page = tab,
                    tabs = tabs,
                    pageOrder = { it.ordinal },
                    previous = if (tab != Tab.SERVERS && !showLogs && !showSplit && !showReceive) Tab.SERVERS else null,
                    onSelect = { tab = it },
                    swipeEnabled = state.settings.swipeTabs && serverQuery == null && !showAdd && !showQr,
                    animationsEnabled = state.settings.animateTabTransitions,
                ) { current ->
                    when (current) {
                        Tab.SERVERS -> ServersScreen(
                            state = state,
                            status = status,
                            onOpenSubscriptions = { tab = Tab.SUBS },
                            modifier = modifier,
                            now = now,
                            onOpenReceive = { showReceive = true },
                            onAdd = { showAdd = true },
                            query = serverQuery.orEmpty(),
                        )
                        Tab.SUBS -> SubscriptionsScreen(
                            state = state,
                            status = status,
                            modifier = modifier.fillMaxSize(),
                            onAdd = { showAdd = true },
                            onOpenReceive = { showReceive = true },
                        )
                        Tab.ROUTING -> RoutingScreen(state = state, modifier = modifier, homeScroll = routingScroll)
                        Tab.SHARING -> SharingScreen(state = state, modifier = modifier.fillMaxSize())
                        Tab.SETTINGS -> SettingsScreen(
                            state = state,
                            status = status,
                            onOpenSplit = { showSplit = true },
                            onOpenSubscriptions = { tab = Tab.SUBS },
                            onOpenLogs = { showLogs = true },
                            onOpenReceive = { showReceive = true },
                            modifier = modifier,
                            scroll = settingsScroll,
                        )
                    }
                }
            }
            }
        }

            if (state.isTv || landscape) {
                TvNavigationRail(
                    current = tab,
                    tabs = tabs,
                    onSelect = { tab = it },
                    activeLabelOnly = !state.isTv && state.settings.dockActiveLabelOnly,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = cutoutStart)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .fillMaxHeight()
                        .width(if (state.isTv) 72.dp else 74.dp),
                )
            }

        }

        if (state.pendingPairing != null) {
            TransferPicker(state)
        }
        TransferProgressDialog(state)

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
                state = state,
                onDone = { showAdd = false },
                onCancel = if (state.isTv) ({ showAdd = false }) else null,

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
}

@Composable
private fun VeilNavigationBar(
    current: Tab,
    tabs: List<Tab>,
    activeLabelOnly: Boolean,
    onSelect: (Tab) -> Unit,
) {
    val shown = if (current in tabs) current else Tab.SETTINGS
    NavigationBar {
        tabs.forEach { entry ->
            NavigationBarItem(
                selected = shown == entry,
                onClick = { onSelect(entry) },
                icon = { TabIcon(entry) },
                label = { Text(tabLabel(entry), maxLines = 1) },
                alwaysShowLabel = !activeLabelOnly,
            )
        }
    }
}

@Composable
private fun tabLabel(entry: Tab): String = when (entry) {
    Tab.SERVERS -> tr("Серверы", "Servers")
    Tab.SUBS -> tr("Источники", "Sources")
    Tab.ROUTING -> tr("Маршрут", "Routing")
    Tab.SHARING -> tr("Раздача", "Sharing")
    Tab.SETTINGS -> tr("Настройки", "Settings")
}

@Composable
private fun TabIcon(entry: Tab) {
    AppIcon(
        icon = when (entry) {
            Tab.SERVERS -> AppIcons.Server
            Tab.SUBS -> AppIcons.Layers
            Tab.ROUTING -> AppIcons.Routing
            Tab.SHARING -> AppIcons.Share
            Tab.SETTINGS -> AppIcons.Settings
        },
        modifier = Modifier.size(24.dp),
    )
}

@Composable
private fun TvNavigationRail(
    current: Tab,
    tabs: List<Tab>,
    onSelect: (Tab) -> Unit,
    activeLabelOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }

    val shown = if (current in tabs) current else Tab.SETTINGS

    LaunchedEffect(shown) {
        repeat(12) {
            delay(180)
            val ok = runCatching { requesters.getValue(shown).requestFocus() }.isSuccess
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
            tabs.forEach { entry ->
                NavigationRailItem(
                    selected = shown == entry,
                    onClick = { onSelect(entry) },
                    icon = { TabIcon(entry) },
                    label = {
                        Text(
                            text = tabLabel(entry),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        )
                    },
                    alwaysShowLabel = !activeLabelOnly,
                    modifier = Modifier
                        .tvFocusRing(RoundedCornerShape(20.dp))
                        .focusRequester(requesters.getValue(entry)),
                )
            }
        }
    }
}
