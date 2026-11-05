package dev.local.veil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import dev.local.veil.core.Appearance
import dev.local.veil.core.SplitMode
import dev.local.veil.platform.PingMode
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.VpnStatus

/** Человеческое состояние туннеля для строки «Туннель». */
private val VpnPhase.label: String
    get() = when (this) {
        VpnPhase.DISCONNECTED -> tr("выключен", "off")
        VpnPhase.CONNECTING -> tr("подключение", "connecting")
        VpnPhase.CONNECTED -> tr("подключено", "connected")
        VpnPhase.PAUSED -> tr("пауза", "paused")
        VpnPhase.ERROR -> tr("ошибка", "error")
    }

@Composable
fun SettingsScreen(
    state: VeilState,
    status: VpnStatus,
    onOpenSplit: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenLogs: () -> Unit = {},
    onOpenReceive: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    val clipboard = LocalClipboardManager.current
    var showAbout by remember { mutableStateOf(false) }
    var confirmNewHwid by remember { mutableStateOf(false) }
    var editingHwid by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        /* ---------------- Подключение ---------------- */

        SettingsGroup(
            title = tr("Подключение", "Connection"),
            icon = AppIcons.Power,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = tr("Подключаться при запуске", "Connect on launch"),
                        subtitle = tr("Сразу поднимать туннель к выбранному узлу", "Bring the tunnel up to the selected server"),
                        checked = settings.autoConnect,
                        onCheckedChange = { state.saveSettings(settings.copy(autoConnect = it)) },
                        icon = AppIcons.Power,
                    )
                },
                {
                    SegmentAction(
                        title = tr("Раздельный туннель", "Split tunneling"),
                        subtitle = when (settings.splitMode) {
                            SplitMode.OFF -> tr("Выключен — весь трафик в VPN", "Off — all traffic goes through the VPN")
                            SplitMode.EXCLUDE -> tr("Все, кроме ${settings.splitPackages.size} прил.", "All except ${settings.splitPackages.size} apps")
                            SplitMode.INCLUDE -> tr("Только ${settings.splitPackages.size} прил.", "Only ${settings.splitPackages.size} apps")
                        },
                        onClick = onOpenSplit,
                        icon = AppIcons.Devices,
                    )
                },
                {
                    SegmentSwitch(
                        title = tr("Уведомление в шторке", "Notification in the shade"),
                        subtitle = tr("Состояние VPN и быстрые действия", "VPN status and quick actions"),
                        checked = settings.liveNotification,
                        onCheckedChange = { state.saveSettings(settings.copy(liveNotification = it)) },
                        icon = AppIcons.Bell,
                    )
                },
            ),
        )

        /* ---------------- Обход блокировок ---------------- */

        SettingsGroup(
            title = tr("Обход блокировок", "Censorship bypass"),
            icon = AppIcons.Shield,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = tr("Фрагментация TLS", "TLS fragmentation"),
                        subtitle = tr("Дробит ClientHello, чтобы DPI не резал рукопожатие", "Splits ClientHello so DPI cannot cut the handshake"),
                        checked = settings.fragment,
                        onCheckedChange = { state.saveSettings(settings.copy(fragment = it)) },
                        icon = AppIcons.ShieldCheck,
                    )
                },
                {
                    SegmentSwitch(
                        // Ярлык к пресету «Bypass Russia» из «Маршрутизации».
                        title = tr("Россия напрямую", "Bypass Russia"),
                        subtitle = tr(
                            "Пресет маршрутизации. Остальные — во вкладке «Маршрут».",
                            "A routing preset. The others live in the Routing tab.",
                        ),
                        checked = settings.routingPreset == dev.local.veil.core.RoutingPreset.BYPASS_RU,
                        onCheckedChange = { on ->
                            val preset = if (on) {
                                dev.local.veil.core.RoutingPreset.BYPASS_RU
                            } else {
                                dev.local.veil.core.RoutingPreset.BYPASS_LAN
                            }
                            state.saveRouting(preset, settings.customRules, settings.blockAds)
                        },
                        icon = AppIcons.Routing,
                    )
                },
            ),
        )

        /* ---------------- Подписки ---------------- */

        SettingsGroup(
            title = tr("Подписки", "Subscriptions"),
            icon = AppIcons.Cloud,
            rows = listOf(
                {
                    SegmentAction(
                        title = if (state.subscriptions.isEmpty()) {
                            tr("Подписок нет", "No subscriptions")
                        } else {
                            tr("Подписок: ${state.subscriptions.size}", "Subscriptions: ${state.subscriptions.size}")
                        },
                        subtitle = tr("Обновить, добавить, удалить", "Refresh, add, remove"),
                        onClick = onOpenSubscriptions,
                        icon = AppIcons.Layers,
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Автообновление", "Auto refresh"),
                        subtitle = tr("Как часто обновлять при запуске", "How often to refresh on launch"),
                        options = listOf(0, 6, 12, 24).map { hours ->
                            SegmentOption(
                                if (hours == 0) tr("выкл", "off") else tr("${hours} ч", "${hours} h"),
                                settings.autoRefreshHours == hours,
                            ) {
                                state.saveSettings(settings.copy(autoRefreshHours = hours))
                            }
                        },
                        icon = AppIcons.Refresh,
                    )
                },
                {
                    SegmentAction(
                        title = tr("Принять с другого устройства", "Receive from another device"),
                        subtitle = tr("QR-код для переноса подписок", "QR code to transfer subscriptions"),
                        onClick = onOpenReceive,
                        icon = AppIcons.Qr,
                    )
                },
                {
                    UserAgentInput(
                        stored = settings.userAgent,
                        onCommit = { state.saveSettings(state.settings.copy(userAgent = it)) },
                    )
                },
            ),
        )

        /* ---------------- Устройство (HWID) ---------------- */

        SettingsGroup(
            title = tr("Устройство", "Device"),
            icon = AppIcons.Transfer,
            rows = buildList<@Composable () -> Unit> {
                add {
                    SegmentSwitch(
                        title = tr("Отправлять HWID", "Send HWID"),
                        subtitle = tr("Нужен провайдерам, которые считают устройства", "Needed by providers that count devices"),
                        checked = settings.sendHwid,
                        onCheckedChange = { state.saveSettings(state.settings.copy(sendHwid = it)) },
                        icon = AppIcons.Shield,
                    )
                }
                if (settings.sendHwid) {
                    add {
                        SegmentAction(
                            title = tr("ID устройства", "Device ID"),
                            subtitle = state.deviceId + " · " + tr("нажмите, чтобы скопировать", "tap to copy"),
                            onClick = {
                                clipboard.setText(AnnotatedString(state.deviceId))
                                state.showBulletin(tr("ID скопирован", "ID copied"), BulletinKind.SUCCESS)
                            },
                            icon = AppIcons.Copy,
                        )
                    }
                    add {
                        SegmentAction(
                            title = tr("Новый ID", "New ID"),
                            subtitle = tr("Для провайдера это новое устройство", "Your provider will see a new device"),
                            onClick = { confirmNewHwid = true },
                            icon = AppIcons.Refresh,
                        )
                    }
                    add {
                        SegmentAction(
                            title = tr("Задать вручную", "Set by hand"),
                            subtitle = tr("ID, который выдал провайдер", "The ID your provider issued"),
                            onClick = { editingHwid = true },
                            icon = AppIcons.Edit,
                        )
                    }
                }
            },
        )

        /* ---------------- Оформление ---------------- */

        SettingsGroup(
            title = tr("Оформление", "Appearance"),
            icon = AppIcons.Moon,
            rows = listOf(
                {
                    SegmentChoice(
                        title = tr("Тема", "Theme"),
                        subtitle = tr("Светлая, тёмная или как в системе", "Light, dark or follow the system"),
                        options = Appearance.entries.map { appearance ->
                            SegmentOption(appearanceTitle(appearance), settings.appearance == appearance) {
                                state.saveSettings(state.settings.copy(appearance = appearance))
                            }
                        },
                        icon = AppIcons.Moon,
                    )
                },
                {
                    SegmentSwitch(
                        title = "Pitch Black",
                        subtitle = tr("Чёрный фон для OLED", "Pure black background for OLED"),
                        checked = settings.pitchBlack,
                        onCheckedChange = { state.saveSettings(state.settings.copy(pitchBlack = it)) },
                        icon = AppIcons.EyeOff,
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Язык", "Language"),
                        subtitle = tr("Язык интерфейса", "Interface language"),
                        options = dev.local.veil.core.AppLanguage.entries.map { language ->
                            val label = if (language == dev.local.veil.core.AppLanguage.SYSTEM) {
                                tr("Системный", "System")
                            } else {
                                language.nativeLabel
                            }
                            SegmentOption(label, settings.language == language) {
                                state.saveSettings(state.settings.copy(language = language))
                            }
                        },
                        icon = AppIcons.Global,
                    )
                },
            ),
        )

        /* ---------------- Пинг ---------------- */

        SettingsGroup(
            title = tr("Пинг", "Ping"),
            icon = AppIcons.Stopwatch,
            rows = listOf(
                {
                    SegmentChoice(
                        title = tr("Способ замера", "Measurement method"),
                        subtitle = pingModeHint(state.pingMode),
                        options = PingMode.entries.map { mode ->
                            SegmentOption(pingModeTitle(mode), state.pingMode == mode) {
                                state.choosePingMode(mode)
                            }
                        },
                        icon = AppIcons.Stopwatch,
                    )
                },
            ),
        )

        /* ---------------- Сеть туннеля ---------------- */

        SettingsGroup(
            title = tr("Сеть туннеля", "Tunnel network"),
            icon = AppIcons.Tuning,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = "IPv6",
                        subtitle = if (settings.ipv6) {
                            tr("IPv6 идёт через VPN", "IPv6 goes through the VPN")
                        } else {
                            tr("Выключено: IPv6 пойдёт мимо VPN", "Off: IPv6 bypasses the VPN")
                        },
                        checked = settings.ipv6,
                        onCheckedChange = { state.saveSettings(state.settings.copy(ipv6 = it)) },
                        icon = AppIcons.Global,
                    )
                },
                {
                    SegmentChoice(
                        title = "MTU",
                        subtitle = tr("Размер пакета в туннеле", "Packet size in the tunnel"),
                        options = listOf(1280, 1400, 1500).map { mtu ->
                            SegmentOption("$mtu", settings.mtu == mtu) {
                                state.saveSettings(state.settings.copy(mtu = mtu))
                            }
                        },
                        icon = AppIcons.Tuning,
                    )
                },
                {
                    ListInput(
                        values = settings.dns,
                        label = tr("DNS, если свой DNS в «Маршруте» выключен", "DNS when custom DNS in Routing is off"),
                        onCommit = { state.saveSettings(state.settings.copy(dns = it)) },
                    )
                },
            ),
        )

        /* ---------------- Диагностика ---------------- */

        SettingsGroup(
            title = tr("Диагностика", "Diagnostics"),
            icon = AppIcons.Note,
            rows = listOf(
                {
                    SegmentAction(
                        title = tr("Проверить соединение", "Test connection"),
                        subtitle = tr("Проверить выбранный узел", "Test the selected server"),
                        onClick = { state.checkConnection() },
                        icon = AppIcons.ShieldCheck,
                    )
                },
                {
                    SegmentAction(
                        title = tr("Логи ядра", "Core logs"),
                        subtitle = tr("Туннель: ${status.phase.label}", "Tunnel: ${status.phase.label}"),
                        onClick = onOpenLogs,
                        icon = AppIcons.Note,
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Уровень лога", "Log level"),
                        subtitle = tr("Подробность диагностики ядра", "Core diagnostics verbosity"),
                        options = listOf(
                            "error" to tr("Ошибки", "Errors"),
                            "warning" to tr("Предупреждения", "Warnings"),
                            "info" to tr("Инфо", "Info"),
                            "debug" to tr("Отладка", "Debug"),
                        ).map { (value, label) ->
                            SegmentOption(label, settings.logLevel == value) {
                                state.saveSettings(state.settings.copy(logLevel = value))
                            }
                        },
                        icon = AppIcons.Filter,
                    )
                },
            ),
        )

        /* ---------------- О приложении ---------------- */

        SettingsGroup(
            title = tr("О приложении", "About"),
            icon = AppIcons.Info,
            rows = listOf(
                {
                    SegmentAction(
                        title = tr("Проверить обновления", "Check for updates"),
                        subtitle = tr("Версия ${state.appVersion} · GitHub Releases", "Version ${state.appVersion} · GitHub Releases"),
                        onClick = { state.checkUpdates() },
                        icon = AppIcons.Update,
                    )
                },
                {
                    SegmentAction(
                        title = "Veil",
                        subtitle = tr("Версия, ядро, исходники", "Version, core, sources"),
                        onClick = { showAbout = true },
                        icon = AppIcons.Info,
                    )
                },
            ),
        )

        Spacer(Modifier.height(20.dp))
    }

    if (confirmNewHwid) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmNewHwid = false },
            title = { Text(tr("Выпустить новый ID?", "Issue a new ID?"), fontWeight = FontWeight.Black) },
            text = { Text(tr("Провайдер увидит новое устройство: оно может занять ещё один слот, а старое останется в списке панели.", "Your provider will see a new device: it may take another slot, and the old one stays in the panel's list.")) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmNewHwid = false
                    state.regenerateDeviceId()
                }) { Text(tr("Новый ID", "New ID")) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmNewHwid = false }) { Text(tr("Отмена", "Cancel")) }
            },
        )
    }

    if (editingHwid) {
        TextDialog(
            title = tr("ID устройства", "Device ID"),
            initial = state.deviceId,
            placeholder = state.deviceId,
            hint = tr("Можно поправить текущий или вставить ID от провайдера. Буквы станут заглавными.", "Edit the current one or paste the ID your provider issued. Letters become uppercase."),
            onDismiss = { editingHwid = false },
            onSave = { value ->
                if (state.setDeviceId(value)) editingHwid = false
            },
        )
    }

    if (showAbout) {
        AlertDialog(
            onDismissRequest = { showAbout = false },
            title = { Text("Veil", fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(tr("Версия ${state.appVersion}", "Version ${state.appVersion}"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("Узлов: ${state.servers.size} · подписок: ${state.subscriptions.size}", "Servers: ${state.servers.size} · subscriptions: ${state.subscriptions.size}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        tr("Туннель: ${status.phase.label}", "Tunnel: ${status.phase.label}"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text(tr("Закрыть", "Close")) }
            },
            dismissButton = {
                TextButton(onClick = { state.openSource() }) { Text(tr("Исходный код", "Source code")) }
            },
        )
    }

    state.update?.let { release ->
        AlertDialog(
            onDismissRequest = { state.dismissUpdate() },
            title = { Text(tr("Доступно ${release.version}", "Available ${release.version}"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (release.notes.isNotBlank()) {
                        Text(
                            release.notes.lines().take(8).joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    val progress = state.updateProgress
                    if (progress != null) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { state.downloadUpdate() },
                    enabled = state.updateProgress == null,
                ) { Text(tr("Скачать и установить", "Download and install")) }
            },
            dismissButton = {
                TextButton(onClick = { state.dismissUpdate() }) { Text(tr("Позже", "Later")) }
            },
        )
    }
}

/**
 * Поле со списком через запятую. Текст живёт локально и сохраняется разобранным:
 * иначе каждое нажатие пересобирало бы строку и съедало запятую с пробелом —
 * второй адрес было не ввести.
 */
@Composable
private fun ListInput(values: List<String>, label: String, onCommit: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(values.joinToString(", ")) }
    SegmentInput(
        value = text,
        onValueChange = { raw ->
            text = raw
            val parsed = raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
            // Пустой список оставил бы туннель без резолвера — храним последний рабочий.
            if (parsed.isNotEmpty()) onCommit(parsed)
        },
        label = label,
    )
}

/**
 * User-Agent подписки. В поле всегда видно то, что реально уйдёт в запросе.
 * Значение по умолчанию (или пустое поле) хранится как "", чтобы UA шёл за
 * версией приложения; всё остальное — свой выбор пользователя.
 */
@Composable
private fun UserAgentInput(stored: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(dev.local.veil.core.effectiveUserAgent(stored)) }
    SegmentInput(
        value = text,
        onValueChange = { raw ->
            text = raw
            val trimmed = raw.trim()
            onCommit(if (trimmed == dev.local.veil.AppVersion.USER_AGENT) "" else trimmed)
        },
        label = tr("User-Agent подписки", "Subscription User-Agent"),
    )
}
