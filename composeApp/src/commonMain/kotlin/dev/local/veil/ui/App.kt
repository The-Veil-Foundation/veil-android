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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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

private enum class Tab(val label: String) {
    SERVERS("Серверы"),
    SUBS("Подписки"),
    SETTINGS("Настройки"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val state = remember(container) { VeilState(container, scope) }
    val status by container.vpn.status.collectAsState()

    OrbitTheme(pitchBlack = state.settings.pitchBlack) {

        var tab by remember { mutableStateOf(Tab.SERVERS) }
        var showSplit by remember { mutableStateOf(false) }
        var showAdd by remember { mutableStateOf(false) }
        var showQr by remember { mutableStateOf(false) }
        var showLogs by remember { mutableStateOf(false) }
        var showReceive by remember { mutableStateOf(false) }

        // Единый тикер на всё приложение: таймер в карточке не зависит от того,
        // пересоздалась ли карточка при прокрутке или возврате в приложение.
        var now by remember { mutableStateOf(currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1000)
                now = currentTimeMillis()
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

        LaunchedEffect(Unit) {
            val hours = state.settings.autoRefreshHours
            if (hours > 0) {
                val now = currentTimeMillis()
                state.subscriptions
                    .filter { now - it.lastFetched > hours * 3_600_000L }
                    .forEach { state.refreshSubscription(it) }
            }
        }

        // Буллетин мягко съезжает, когда нижняя навигация прячется.
        val bulletinBottom by animateDpAsState(
            targetValue = if (showSplit || showLogs || showReceive) 24.dp else 104.dp,
            animationSpec = tween(180),
            label = "bulletin-pad",
        )

        Box(Modifier.fillMaxSize()) {
            Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
              // Панели сменяют друг друга коротким слайдом без отскока.
              AnimatedContent(
                targetState = if (showLogs) 100 else if (showSplit) 101 else if (showReceive) 102 else tab.ordinal,
                transitionSpec = {
                    (fadeIn(tween(180)) + slideInVertically(tween(200)) { -it / 4 }) togetherWith
                        fadeOut(tween(140))
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
                                AppIcon(AppIcons.ArrowLeft, contentDescription = "Назад")
                            }
                        },
                        title = { Text("Логи ядра", fontWeight = FontWeight.Black) },
                    )
                } else if (bar == 101) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showSplit = false }) {
                                AppIcon(AppIcons.ArrowLeft, contentDescription = "Назад")
                            }
                        },
                        title = { Text("Раздельный туннель", fontWeight = FontWeight.Black) },
                    )
                } else if (bar == 102) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showReceive = false }) {
                                AppIcon(AppIcons.ArrowLeft, contentDescription = "Назад")
                            }
                        },
                        title = { Text("Принять с другого устройства", fontWeight = FontWeight.Black) },
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
                                        Tab.SUBS -> "Подписки"
                                        else -> "Настройки"
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
                AnimatedVisibility(
                    visible = !showSplit && !showLogs && !showReceive,
                    enter = slideInVertically(tween(180)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(180)) { it } + fadeOut(tween(150)),
                ) {
                    VeilNavigationBar(current = tab, onSelect = { tab = it })
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
                        AppIcon(AppIcons.Add, contentDescription = "Добавить")
                    }
                }
            },
        ) { insets ->
            val modifier = Modifier.padding(insets)

            // Логи и раздельный туннель выезжают справа, основной экран отъезжает назад.
            AnimatedContent(
                targetState = if (showLogs) 2 else if (showSplit) 1 else if (showReceive) 3 else 0,
                transitionSpec = {
                    if (targetState > initialState) {
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
                title = { Text("Отправить на приставку?", fontWeight = FontWeight.Black) },
                text = {
                    Text(
                        "Подписок: ${state.subscriptions.size}, серверов вручную: ${state.servers.count { it.source == null }}. " +
                            "Они уйдут на устройство в вашей локальной сети.",
                    )
                },
                confirmButton = { TextButton(onClick = state::confirmTransfer) { Text("Отправить") } },
                dismissButton = { TextButton(onClick = state::dismissTransfer) { Text("Отмена") } },
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

        if (showAdd) {
            ModalBottomSheet(
                onDismissRequest = { showAdd = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
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

    Column(
        Modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Добавить", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp),
            shape = RoundedCornerShape(20.dp),
            label = { Text("Ссылка на подписку или share-ссылки") },
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
                Text("Сканировать QR-код")
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
                Text("Принять с другого устройства")
            }
        }

        Text(
            text = "Ссылка, base64 или конфиг Xray.",
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
        ) { Text(if (busy) "Загружаю…" else "Добавить", fontWeight = FontWeight.Bold) }
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
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
                label = { Text(entry.label) },
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
            Tab.SUBS -> AppIcons.Cloud
            Tab.SETTINGS -> AppIcons.Settings
        },
        modifier = Modifier
            .size(24.dp)
            .graphicsLayer { scaleX = pop; scaleY = pop },
    )
}
