package dev.local.veil.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ViewList
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
        PlatformBackHandler(enabled = showQr || showAdd || showLogs || showSplit || tab != Tab.SERVERS) {
            when {
                showQr -> showQr = false
                showAdd -> showAdd = false
                showLogs -> showLogs = false
                showSplit -> showSplit = false
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

        Box(Modifier.fillMaxSize()) {
            Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                if (showLogs) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showLogs = false }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                            }
                        },
                        title = { Text("Логи ядра", fontWeight = FontWeight.Black) },
                    )
                } else if (showSplit) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                        navigationIcon = {
                            IconButton(onClick = { showSplit = false }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Назад")
                            }
                        },
                        title = { Text("Раздельный туннель", fontWeight = FontWeight.Black) },
                    )
                } else if (tab == Tab.SERVERS) {
                    LargeTopAppBar(
                        colors = TopAppBarDefaults.largeTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                            scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        scrollBehavior = scrollBehavior,
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                VeilMark(40.dp)
                                Spacer(Modifier.size(10.dp))
                                Text("Veil", fontWeight = FontWeight.Black)
                            }
                        },
                        actions = {
                            CoreChip(state, status)
                            IconButton(
                                onClick = { state.refreshAll() },
                                enabled = state.subscriptions.isNotEmpty() && !state.busy,
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = "Обновить подписки")
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
                                    text = when (tab) {
                                        Tab.SUBS -> "Подписки"
                                        else -> "Настройки"
                                    },
                                    fontWeight = FontWeight.Black,
                                )
                            }
                        },
                        actions = { CoreChip(state, status) },
                    )
                }
            },
            bottomBar = {
                if (!showSplit && !showLogs) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = tab == entry,
                                onClick = { tab = entry },
                                icon = {
                                    Icon(
                                        imageVector = when (entry) {
                                            Tab.SERVERS -> Icons.Rounded.ViewList
                                            Tab.SUBS -> Icons.Rounded.CloudSync
                                            Tab.SETTINGS -> Icons.Rounded.Settings
                                        },
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(entry.label) },
                            )
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!showSplit && (tab == Tab.SERVERS || tab == Tab.SUBS)) {
                    FloatingActionButton(onClick = { showAdd = true }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Добавить")
                    }
                }
            },
        ) { insets ->
            val modifier = Modifier.padding(insets)

            if (showLogs) {
                LogsScreen(
                    provider = container.logs,
                    modifier = modifier.fillMaxSize(),
                    onBack = { showLogs = false },
                    onBulletin = { state.showBulletin(it) },
                )
            } else if (showSplit) {
                SplitTunnelScreen(state = state, modifier = modifier.fillMaxSize())
            } else {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        // Соседние вкладки едут в сторону движения: влево/вправо
                        // с лёгким пружинным перелётом, как в M3 Expressive.
                        val forward = targetState.ordinal > initialState.ordinal
                        val shift = { full: Int -> if (forward) full / 7 else -full / 7 }
                        (slideInHorizontally(
                            animationSpec = spring(dampingRatio = 0.85f, stiffness = 420f),
                            initialOffsetX = shift,
                        ) + fadeIn(tween(220))) togetherWith
                            (slideOutHorizontally(
                                animationSpec = tween(170),
                                targetOffsetX = { full -> -shift(full) },
                            ) + fadeOut(tween(140)))
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
                        )
                        Tab.SUBS -> SubscriptionsScreen(state = state, modifier = modifier.fillMaxSize())
                        Tab.SETTINGS -> SettingsScreen(
                            state = state,
                            status = status,
                            onOpenSplit = { showSplit = true },
                            onOpenSubscriptions = { tab = Tab.SUBS },
                            onOpenLogs = { showLogs = true },
                            modifier = modifier,
                        )
                    }
                }
            }
        }

            BulletinHost(
                bulletin = state.bulletin,
                onDismiss = state::dismissBulletin,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = if (showSplit || showLogs) 24.dp else 104.dp),
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
                    onScan = if (qrSupported) {
                        { showQr = true }
                    } else {
                        null
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
                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Сканировать QR-код")
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

@Composable
private fun CoreChip(state: VeilState, status: dev.local.veil.platform.VpnStatus) {
    val version = (status.coreVersion ?: state.coreVersion)
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = version?.let { "Xray $it" } ?: "ядро нет",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
