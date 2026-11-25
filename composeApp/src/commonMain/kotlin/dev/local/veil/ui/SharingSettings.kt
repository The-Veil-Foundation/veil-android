package dev.local.veil.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.veil.platform.SharingMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SharingSettings(state: VeilState) {
    val sharing = state.sharing ?: return
    val status by sharing.status.collectAsState()
    var show by remember { mutableStateOf(false) }
    SettingsGroup(
        title = tr("Раздача интернета", "Internet sharing"),
        icon = AppIcons.Share,
        rows = listOf({
            SegmentAction(
                title = tr("Раздача через VPN", "Share VPN connection"),
                subtitle = when {
                    status.busy -> tr("Настройка…", "Setting up…")
                    status.active && status.mode == SharingMode.SHIZUKU -> "Shizuku · " + tr("включено", "on")
                    status.active -> "${status.host}:${status.port}"
                    else -> tr("Выключено", "Off")
                },
                icon = AppIcons.Power,
                onClick = { show = true },
            )
        }),
    )
    if (show) {
        ModalBottomSheet(
            onDismissRequest = { show = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = RoundedCornerShape(topStart = 36.dp, topEnd = 36.dp),
        ) {
            SharingScreen(state, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun SharingScreen(state: VeilState, modifier: Modifier = Modifier) {
    val sharing = state.sharing ?: return
    val status by sharing.status.collectAsState()
    val shizuku by sharing.shizuku.collectAsState()
    val vpnConnected by sharing.vpnConnected.collectAsState()
    val clipboard = LocalClipboardManager.current
    var mode by remember { mutableStateOf(if (sharing.shizukuSupported) SharingMode.SHIZUKU else SharingMode.PROXY) }
    var details by remember { mutableStateOf(false) }
    var revealPassword by remember { mutableStateOf(false) }
    var addresses by remember { mutableStateOf(sharing.addresses()) }
    var address by remember { mutableStateOf(addresses.firstOrNull().orEmpty()) }
    val locked = status.active || status.busy
    val selectedMode = if (locked && status.mode != SharingMode.OFF) status.mode else mode
    val experimental = selectedMode == SharingMode.SHIZUKU
    val reduced = LocalReduceMotion.current || LocalTvMode.current

    LaunchedEffect(sharing) {
        while (isActive) {
            sharing.refreshShizuku()
            val currentAddresses = sharing.addresses()
            addresses = currentAddresses
            if (address !in currentAddresses) address = currentAddresses.firstOrNull().orEmpty()
            delay(3000)
        }
    }
    LaunchedEffect(status.active, status.password) { revealPassword = false }
    LaunchedEffect(status.mode) { if (status.mode != SharingMode.OFF) mode = status.mode }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CookieBadge(diameter = 64.dp, color = MaterialTheme.colorScheme.secondaryContainer) {
                        AppIcon(
                            if (status.active) AppIcons.Share else AppIcons.Power,
                            modifier = Modifier.size(30.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            when {
                                status.busy -> tr("Подготовка…", "Setting up…")
                                status.active -> tr("Раздача включена", "Sharing is on")
                                else -> tr("Раздача выключена", "Sharing is off")
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (experimental) "Shizuku · " + tr("экспериментально", "experimental") else "HTTP / SOCKS5",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (status.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (status.message.isNotBlank()) {
                    Text(status.message, style = MaterialTheme.typography.bodyMedium)
                } else if (!status.active) {
                    Text(
                        if (vpnConnected) tr("VPN подключён · можно запускать", "VPN connected · ready to start")
                        else tr("Сначала подключите VPN", "Connect VPN first"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ExpressiveActionButton(
                    onClick = {
                        when {
                            locked -> sharing.stop()
                            experimental -> sharing.startShizuku()
                            else -> sharing.startProxy(address)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    active = locked,
                    enabled = locked || (vpnConnected && if (experimental) {
                        sharing.shizukuSupported && shizuku.running && shizuku.authorized
                    } else address in addresses),
                ) {
                    AppIcon(if (locked) AppIcons.Close else AppIcons.Power)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (locked) tr("Остановить раздачу", "Stop sharing") else tr("Включить раздачу", "Start sharing"),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SharingModeCard(
                title = "Shizuku",
                caption = tr("Android 13+", "Android 13+"),
                icon = AppIcons.Bolt,
                selected = experimental,
                enabled = !locked && sharing.shizukuSupported,
                onClick = { mode = SharingMode.SHIZUKU },
                modifier = Modifier.weight(1f),
            )
            SharingModeCard(
                title = tr("Прокси", "Proxy"),
                caption = tr("Android 7+", "Android 7+"),
                icon = AppIcons.Global,
                selected = !experimental,
                enabled = !locked,
                onClick = { mode = SharingMode.PROXY },
                modifier = Modifier.weight(1f),
            )
        }

        if (experimental) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Shizuku", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        IconButton(onClick = sharing::refreshShizuku) {
                            AppIcon(AppIcons.Refresh, contentDescription = tr("Обновить статус", "Refresh status"))
                        }
                    }
                    SharingReadinessRow(
                        AppIcons.Power,
                        tr("Сервис", "Service"),
                        if (shizuku.running) tr("Запущен", "Running") else tr("Не запущен", "Not running"),
                        shizuku.running,
                    )
                    SharingReadinessRow(
                        AppIcons.ShieldCheck,
                        tr("Доступ Veil", "Veil access"),
                        if (shizuku.authorized) tr("Разрешён", "Granted") else tr("Не разрешён", "Not granted"),
                        shizuku.authorized,
                    )
                    if (shizuku.running) {
                        Text(
                            if (shizuku.root) tr("Запущен через root", "Started with root") else tr("Запущен через ADB", "Started with ADB"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!shizuku.running) {
                        Text(
                            tr("Запустите Shizuku через USB ADB, беспроводную отладку или root. Wi-Fi для запуска через USB не нужен.",
                                "Start Shizuku with USB ADB, wireless debugging or root. USB startup does not require Wi-Fi."),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FilledTonalButton(onClick = sharing::openShizuku, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
                            AppIcon(AppIcons.Bolt, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Открыть Shizuku", "Open Shizuku"))
                        }
                    } else if (!shizuku.authorized) {
                        FilledTonalButton(onClick = sharing::authorizeShizuku, enabled = !status.busy, modifier = Modifier.fillMaxWidth()) {
                            AppIcon(AppIcons.ShieldCheck, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(tr("Разрешить доступ Veil", "Grant Veil access"))
                        }
                    }
                }
            }
            SharingNotice(
                tr("Экспериментальный режим", "Experimental mode"),
                tr("Android может кратковременно пустить трафик мимо VPN. При потере Shizuku проверьте и выключите точку доступа вручную.",
                    "Android may briefly route traffic outside VPN. If Shizuku is lost, check and turn off the hotspot manually."),
            )
        } else {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr("Адрес телефона", "Phone address"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    if (addresses.isEmpty()) {
                        Text(tr("Включите точку доступа, чтобы появился локальный адрес.", "Enable the hotspot to get a local address."), style = MaterialTheme.typography.bodySmall)
                    }
                    addresses.forEach { candidate ->
                        Surface(
                            onClick = { address = candidate },
                            enabled = !locked,
                            shape = RoundedCornerShape(16.dp),
                            color = if (address == candidate) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(candidate, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (address == candidate) AppIcon(AppIcons.Check, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    TextButton(onClick = sharing::openHotspotSettings, enabled = !status.busy) {
                        AppIcon(AppIcons.Wifi, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("Настройки точки доступа", "Hotspot settings"))
                    }
                }
            }
            if (status.active && status.mode == SharingMode.PROXY) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(tr("Подключение устройств", "Connect your devices"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("HTTP / SOCKS5 · ${status.host}:${status.port}", style = MaterialTheme.typography.bodyLarge)
                        Text(tr("Логин", "Username") + ": ${status.username}")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Пароль", "Password") + ": " + if (revealPassword) status.password else "••••••••", modifier = Modifier.weight(1f))
                            IconButton(onClick = { revealPassword = !revealPassword }) {
                                AppIcon(if (revealPassword) AppIcons.EyeOff else AppIcons.Eye,
                                    contentDescription = if (revealPassword) tr("Скрыть пароль", "Hide password") else tr("Показать пароль", "Show password"))
                            }
                        }
                        FilledTonalButton(onClick = {
                            clipboard.setText(AnnotatedString("HTTP / SOCKS5: ${status.host}:${status.port}\n${status.username}\n${status.password}"))
                            state.showBulletin(tr("Параметры прокси скопированы", "Proxy details copied"))
                        }, modifier = Modifier.fillMaxWidth()) {
                            AppIcon(AppIcons.Copy, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                            Text(tr("Скопировать параметры", "Copy connection details"))
                        }
                    }
                }
            }
            SharingNotice(
                tr("Нужна настройка на устройствах", "Client setup required"),
                tr("Через VPN идут только приложения с поддержкой прокси. Остальные могут подключаться напрямую.",
                    "Only proxy-aware apps use VPN. Other apps may connect directly."),
            )
        }

        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(if (reduced) Modifier else Modifier.animateContentSize()) {
                TextButton(onClick = { details = !details }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                    AppIcon(AppIcons.Info, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Как это работает", "How it works"), modifier = Modifier.weight(1f))
                    AppIcon(if (details) AppIcons.ChevronUp else AppIcons.ChevronDown)
                }
                if (details) {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            if (experimental) tr(
                                "Перед запуском выключите обычную точку доступа. Veil подготовит туннель и включит её сам. На подключённых устройствах прокси настраивать не нужно. Работа зависит от прошивки.",
                                "Turn off the existing hotspot first. Veil prepares the tunnel and starts it for you. Connected devices need no proxy configuration. Support depends on the firmware.",
                            ) else tr(
                                "Включите точку доступа Android и выберите её IP. На клиенте задайте HTTP или SOCKS5, порт, логин и пароль из карточки подключения. Не все системные настройки Wi-Fi поддерживают пароль прокси.",
                                "Enable the Android hotspot and select its IP. Enter HTTP or SOCKS5, port, username and password on the client. Some system Wi-Fi proxy settings do not support authentication.",
                            ), style = MaterialTheme.typography.bodySmall,
                        )
                        Text(tr("Раздача использует выбранный VPN-сервер и ваши правила маршрутизации, включая прямые подключения.",
                            "Sharing uses the selected VPN server and your routing rules, including direct connections."), style = MaterialTheme.typography.bodySmall)
                        Text(tr("Отключение, пауза или смена VPN-сервера останавливает раздачу. После этого включите её снова.",
                            "Disconnecting, pausing or changing the VPN server stops sharing. Start sharing again afterwards."), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharingModeCard(title: String, caption: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (selected) 28.dp else 20.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(icon)
                Spacer(Modifier.weight(1f))
                if (selected) AppIcon(AppIcons.Check, modifier = Modifier.size(18.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SharingReadinessRow(icon: ImageVector, title: String, value: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppIcon(icon, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AppIcon(if (ready) AppIcons.Check else AppIcons.Close, modifier = Modifier.size(16.dp),
            tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun SharingNotice(title: String, text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .45f)) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AppIcon(AppIcons.Danger, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
