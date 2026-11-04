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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        /* ---------------- Обновления ---------------- */

        SettingsGroup(
            title = tr("Обновления", "Updates"),
            icon = AppIcons.Update,
            rows = listOf(
                {
                    SegmentAction(
                        title = tr("Проверить обновления", "Check for updates"),
                        subtitle = tr("Версия ${state.appVersion} · GitHub Releases", "Version ${state.appVersion} · GitHub Releases"),
                        onClick = { state.checkUpdates() },
                    )
                },
            ),
        )

        /* ---------------- Оформление ---------------- */

        SettingsGroup(
            title = tr("Оформление", "Appearance"),
            icon = AppIcons.Moon,
            rows = listOf(
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
                                state.saveSettings(settings.copy(language = language))
                            }
                        },
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Тема", "Theme"),
                        subtitle = tr("Светлая, тёмная или как в системе", "Light, dark or follow the system"),
                        options = Appearance.entries.map { appearance ->
                            SegmentOption(appearanceTitle(appearance), settings.appearance == appearance) {
                                state.saveSettings(settings.copy(appearance = appearance))
                            }
                        },
                    )
                },
                {
                    SegmentSwitch(
                        title = "Pitch Black",
                        subtitle = tr("Чёрный фон для OLED", "Pure black background for OLED"),
                        checked = settings.pitchBlack,
                        onCheckedChange = { state.saveSettings(settings.copy(pitchBlack = it)) },
                    )
                },
            ),
        )

        /* ---------------- Диагностика ---------------- */

        SettingsGroup(
            title = tr("Диагностика", "Diagnostics"),
            icon = AppIcons.Tuning,
            rows = listOf(
                {
                    SegmentAction(
                        title = tr("Логи ядра", "Core logs"),
                        subtitle = tr("Просмотр и копирование", "View and copy"),
                        onClick = onOpenLogs,
                    )
                },
                {
                    SegmentAction(
                        title = tr("Проверить соединение", "Test connection"),
                        subtitle = tr("Проверить выбранный узел", "Test the selected server"),
                        onClick = { state.checkConnection() },
                    )
                },
                {
                    SegmentAction(
                        title = tr("О приложении", "About"),
                        subtitle = tr("Версия, ядро, исходники", "Version, core, sources"),
                        onClick = { showAbout = true },
                    )
                },
            ),
        )

        /* ---------------- Туннель ---------------- */

        SettingsGroup(
            title = tr("Туннель", "Tunnel"),
            icon = AppIcons.Global,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = tr("Подключаться при запуске", "Connect on launch"),
                        subtitle = tr("Сразу поднимать туннель к выбранному узлу", "Bring the tunnel up to the selected server"),
                        checked = settings.autoConnect,
                        onCheckedChange = { state.saveSettings(settings.copy(autoConnect = it)) },
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
                                state.saveSettings(settings.copy(logLevel = value))
                            }
                        },
                    )
                },
                {
                    SegmentChoice(
                        title = "MTU",
                        subtitle = tr("Размер пакета", "Packet size"),
                        options = listOf(1280, 1400, 1500).map { mtu ->
                            SegmentOption("$mtu", settings.mtu == mtu) {
                                state.saveSettings(settings.copy(mtu = mtu))
                            }
                        },
                    )
                },
                {
                    SegmentSwitch(
                        title = tr("IPv6 в туннеле", "IPv6 in tunnel"),
                        subtitle = tr("IPv6 пойдёт мимо VPN", "IPv6 will bypass the VPN"),
                        checked = settings.ipv6,
                        onCheckedChange = { state.saveSettings(settings.copy(ipv6 = it)) },
                    )
                },
                {
                    SegmentInput(
                        value = settings.dns.joinToString(", "),
                        onValueChange = { raw ->
                            state.saveSettings(
                                settings.copy(
                                    dns = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                                ),
                            )
                        },
                        label = "DNS",
                    )
                },
                {
                    SegmentInput(
                        value = settings.userAgent,
                        onValueChange = { state.saveSettings(settings.copy(userAgent = it)) },
                        label = tr("User-Agent подписки", "Subscription User-Agent"),
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
                        subtitle = tr("Дробление ClientHello, обход DPI", "Split ClientHello to evade DPI"),
                        checked = settings.fragment,
                        onCheckedChange = { state.saveSettings(settings.copy(fragment = it)) },
                    )
                },
                {
                    SegmentSwitch(
                        title = tr("Российские домены напрямую", "Russian domains direct"),
                        subtitle = tr(".ru, .su, .рф — мимо туннеля", ".ru, .su, .рф bypass the tunnel"),
                        checked = settings.bypassRu,
                        onCheckedChange = { state.saveSettings(settings.copy(bypassRu = it)) },
                    )
                },
            ),
        )

        /* ---------------- Раздельный туннель ---------------- */

        SettingsGroup(
            title = tr("Раздельный туннель", "Split tunneling"),
            icon = AppIcons.Devices,
            rows = listOf(
                {
                    SegmentAction(
                        title = when (settings.splitMode) {
                            SplitMode.OFF -> tr("Выключен", "Off")
                            SplitMode.EXCLUDE -> tr("Все, кроме ${settings.splitPackages.size} прил.", "All except ${settings.splitPackages.size} apps")
                            SplitMode.INCLUDE -> tr("Только ${settings.splitPackages.size} прил.", "Only ${settings.splitPackages.size} apps")
                        },
                        subtitle = when (settings.splitMode) {
                            SplitMode.OFF -> tr("Весь трафик идёт в VPN", "All traffic goes through the VPN")
                            else -> tr("Выбрать приложения", "Choose apps")
                        },
                        onClick = onOpenSplit,
                    )
                },
            ),
        )

        /* ---------------- Подписка ---------------- */

        SettingsGroup(
            title = tr("Подписка", "Subscription"),
            icon = AppIcons.Cloud,
            rows = listOf(
                {
                    SegmentAction(
                        title = if (state.subscriptions.isEmpty()) {
                            tr("Подписок нет", "No subscriptions")
                        } else {
                            tr("Подписок: ${state.subscriptions.size}", "Subscriptions: ${state.subscriptions.size}")
                        },
                        subtitle = tr("Обновление, добавление, удаление", "Refresh, add, remove"),
                        onClick = onOpenSubscriptions,
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Автообновление", "Auto refresh"),
                        subtitle = tr("Как часто обновлять при запуске", "How often to refresh on launch"),
                        options = listOf(0, 6, 12, 24).map { hours ->
                            SegmentOption(
                                if (hours == 0) tr("выкл", "off") else tr("${hours}ч", "${hours}h"),
                                settings.autoRefreshHours == hours,
                            ) {
                                state.saveSettings(settings.copy(autoRefreshHours = hours))
                            }
                        },
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
                    )
                },
            ),
        )

        /* ---------------- Уведомления ---------------- */

        SettingsGroup(
            title = tr("Уведомления", "Notifications"),
            icon = AppIcons.Bell,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = tr("Показывать в шторке", "Show in notification shade"),
                        subtitle = tr("Состояние VPN и быстрые действия", "VPN status and quick actions"),
                        checked = settings.liveNotification,
                        onCheckedChange = { state.saveSettings(settings.copy(liveNotification = it)) },
                    )
                },
            ),
        )

        /* ---------------- Перенос ---------------- */

        SettingsGroup(
            title = tr("Перенос", "Transfer"),
            icon = AppIcons.Qr,
            rows = listOf(
                {
                    SegmentAction(
                        title = tr("Принять с другого устройства", "Receive from another device"),
                        subtitle = tr("QR-код для переноса подписок", "QR code to transfer subscriptions"),
                        onClick = onOpenReceive,
                    )
                },
            ),
        )

        /* ---------------- Устройство ---------------- */

        SettingsGroup(
            title = tr("Устройство", "Device"),
            icon = AppIcons.Box,
            rows = listOf(
                { SegmentInfo(tr("ID устройства", "Device ID"), state.deviceId.take(18) + "…") },
                {
                    SegmentAction(
                        title = tr("Копировать ID", "Copy ID"),
                        subtitle = tr("Для панелей, которые требуют HWID", "For panels that require HWID"),
                        onClick = {
                            clipboard.setText(AnnotatedString(state.deviceId))
                            state.showBulletin(tr("ID скопирован", "ID copied"), BulletinKind.SUCCESS)
                        },
                    )
                },
            ),
        )

        /* ---------------- Состояние ---------------- */

        SettingsGroup(
            title = tr("Состояние", "Status"),
            icon = AppIcons.Info,
            rows = listOf(
                { SegmentInfo(tr("Профилей", "Profiles"), state.servers.size.toString()) },
                { SegmentInfo(tr("Туннель", "Tunnel"), status.phase.label) },
                { SegmentInfo(tr("Лог", "Log"), "files/xray.log") },
            ),
        )

        Spacer(Modifier.height(20.dp))
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
                        "Туннель: ${if (status.isConnected) "поднят" else tr("выключен", "off")}",
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
