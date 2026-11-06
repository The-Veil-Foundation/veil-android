package dev.local.veil.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.material3.NavigationBarItemDefaults
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
import dev.local.veil.platform.isLowRamDevice
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
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
    val foreground by container.vpn.foreground.collectAsState()
    // Composable-вызов безусловный: если звать isSystemInDarkTheme() только в
    // ветке SYSTEM, смена темы меняет структуру групп, и Compose сбрасывает
    // всё состояние ниже — вкладка прыгала на «Серверы».
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val darkTheme = when (state.settings.appearance) {
        dev.local.veil.core.Appearance.LIGHT -> false
        dev.local.veil.core.Appearance.DARK -> true
        dev.local.veil.core.Appearance.SYSTEM -> state.isTv || systemDark
    }

    // Навигация живёт выше обёрток темы и языка: их смена не должна
    // пересоздавать экран, на котором стоит пользователь.
    var tab by remember { mutableStateOf(Tab.SERVERS) }
    // Прокрутка вкладок держится здесь: подэкраны и смена вкладки убирают
    // экран из композиции, и без этого он возвращался в самое начало.
    val settingsScroll = rememberScrollState()
    val routingScroll = rememberScrollState()
    // «Источники» по умолчанию спрятаны, как на macOS после настройки
    // подписок. Вкладка всё равно открывается из настроек: там ряд «Подписки».
    val tabs = if (state.settings.showSourcesTab) Tab.entries else Tab.entries - Tab.SUBS
    // Ссылка снаружи (veil://pair со страницы приставки) — как вставленная руками.
    val incoming by dev.local.veil.core.IncomingLinks.link.collectAsState()
    LaunchedEffect(incoming) {
        dev.local.veil.core.IncomingLinks.take()?.let { state.addInput(it) }
    }
    var showSplit by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    // Поиск серверов живёт в верхней панели; null — поле закрыто.
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
    // Иконки статус-бара идут за анимированным фоном, а не за выбором темы:
    // иначе на время перехода тёмные иконки ложатся на ещё тёмный фон.
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

        // Единый тикер на всё приложение: таймер в карточке не зависит от того,
        // пересоздалась ли карточка при прокрутке или возврате в приложение.
        // Тикаем только пока идёт сессия и приложение на экране: в фоне
        // незачем будить композицию каждую секунду.
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

        // Жест «назад» сначала закрывает шторку/вложенный экран и возвращает
        // на «Серверы», и только потом выпускает из приложения.
        PlatformBackHandler(enabled = showQr || showAdd || showLogs || showSplit || showReceive || serverQuery != null || tab != Tab.SERVERS) {
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

        // Ошибка ядра показывается буллетином и не висит в списке.
        LaunchedEffect(status.message, status.phase) {
            val message = status.message
            if (status.phase == VpnPhase.ERROR && !message.isNullOrBlank()) {
                state.showBulletin(message, BulletinKind.ERROR)
            }
        }
        val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

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
                .then(
                    if (state.isTv) Modifier.padding(horizontal = 28.dp, vertical = 16.dp)
                    else Modifier,
                ),
        ) {
            // Адаптив M3: в ландшафте нижняя панель заменяется боковым rail.
            val landscape = !state.isTv && maxWidth > maxHeight
            // Вырез под камеру в ландшафте оказывается сбоку: отодвигаем от него
            // и rail, и контент, иначе иконки уходят под объектив.
            // Берём именно «добавку» выреза поверх системных баров, чтобы не
            // задвоить отступы статус-бара/навбара.
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
            // Буллетин мягко съезжает, когда нижняя навигация прячется.
            val bulletinBottom by animateDpAsState(
                targetValue = if (state.isTv || landscape || showSplit || showLogs || showReceive) 24.dp else 104.dp,
                animationSpec = tween(180),
                label = "bulletin-pad",
            )
            Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    when {
                        state.isTv -> Modifier.padding(start = 88.dp)
                        landscape -> Modifier.padding(start = 78.dp + cutoutStart, end = cutoutEnd)
                        else -> Modifier
                    },
                )
                .then(
                    // Верхняя панель не сворачивается: подключение уезжает вместе
                    // со списком, а сверху его заменяет компактная полоса.
                    Modifier,
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
                        title = {
                            val query = serverQuery
                            if (query != null) {
                                SearchPill(
                                    query = query,
                                    onQueryChange = { serverQuery = it },
                                    autoFocus = true,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    VeilMark(34.dp)
                                    Spacer(Modifier.size(10.dp))
                                    Text("Veil", fontWeight = FontWeight.Black)
                                }
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
                if (!state.isTv && !landscape) {
                AnimatedVisibility(
                    visible = !showSplit && !showLogs && !showReceive,
                    enter = slideInVertically(tween(180)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150)),
                ) {
                    VeilNavigationBar(current = tab, tabs = tabs, onSelect = { tab = it })
                }
                }
            },
            floatingActionButton = {
                AnimatedVisibility(
                    visible = !showSplit && !showReceive && ((tab == Tab.SERVERS && state.servers.isNotEmpty()) || (tab == Tab.SUBS && (state.servers.isNotEmpty() || state.subscriptions.isNotEmpty()))),
                    enter = scaleIn(tween(180)) + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    // Скруглённый квадрат: спокойная геометрия, без морфинга.
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
                        // M3 fade through для нижней навигации: старый экран быстро
                        // гаснет, новый проявляется после него с лёгким масштабом.
                        // Два экрана не просвечивают друг сквозь друга — нет «мыла».
                        (fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing)) +
                            scaleIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing), initialScale = 0.96f)) togetherWith
                            fadeOut(tween(90, easing = FastOutLinearInEasing))
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
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = cutoutStart)
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .fillMaxHeight()
                        .width(if (state.isTv) 72.dp else 74.dp),
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

        // QR приставки или «Открыть в Veil» на её странице: спрашиваем, что
        // отправить, прежде чем ключи уйдут в сеть.
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
}

/** Нижняя навигация: Solar-иконки, выбранная чуть крупнее — без подпрыгивания. */
@Composable
private fun VeilNavigationBar(current: Tab, tabs: List<Tab>, onSelect: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(30.dp)
    // M3 Expressive: плавающая тональная панель вместо приклеенной к краю.
    Box(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        NavigationBar(
            containerColor = scheme.surfaceContainerHigh,
            tonalElevation = 2.dp,
            windowInsets = WindowInsets(0),
            modifier = Modifier
                .fillMaxWidth()
                .height(78.dp)
                .shadow(10.dp, pill, clip = false)
                .clip(pill),
        ) {
            val shown = if (current in tabs) current else Tab.SETTINGS
            tabs.forEach { entry ->
                val selected = shown == entry
                NavigationBarItem(
                    selected = selected,
                    onClick = { onSelect(entry) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = scheme.onSecondaryContainer,
                        selectedTextColor = scheme.onSurface,
                        indicatorColor = scheme.secondaryContainer,
                        unselectedIconColor = scheme.onSurfaceVariant,
                        unselectedTextColor = scheme.onSurfaceVariant,
                    ),
                    icon = { TabIcon(entry = entry, selected = selected) },
                    label = {
                        Text(
                            text = when (entry) {
                                Tab.SERVERS -> tr("Серверы", "Servers")
                                Tab.SUBS -> tr("Источники", "Sources")
                                Tab.ROUTING -> tr("Маршрут", "Routing")
                                Tab.SETTINGS -> tr("Настройки", "Settings")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun TabIcon(entry: Tab, selected: Boolean) {
    // M3 Expressive: выбор вкладки — быстрая spatial-пружина, без «прыжка».
    val pop by animateFloatAsState(
        targetValue = if (selected) 1.14f else 1f,
        animationSpec = VeilMotion.fastSpatial,
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
    tabs: List<Tab>,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    // Скрытые «Источники» открываются из настроек: на рельсе их нет, поэтому
    // подсвечен и держит фокус пункт «Настройки», а не случайный первый.
    val shown = if (current in tabs) current else Tab.SETTINGS
    // На телевизоре первый кадр не сразу принимает фокус: пробуем несколько раз,
    // иначе пульт «застревает» на поле поиска или вообще ни на чём.
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
                    icon = { TabIcon(entry = entry, selected = shown == entry) },
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
                    modifier = Modifier
                        .tvFocusRing(RoundedCornerShape(20.dp))
                        .focusRequester(requesters.getValue(entry)),
                )
            }
        }
    }
}
