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
import dev.local.veil.core.SplitMode
import dev.local.veil.platform.PingMode
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.VpnStatus

/** Человеческое состояние туннеля для строки «Туннель». */
private val VpnPhase.label: String
    get() = when (this) {
        VpnPhase.DISCONNECTED -> "выключен"
        VpnPhase.CONNECTING -> "подключение"
        VpnPhase.CONNECTED -> "подключено"
        VpnPhase.PAUSED -> "пауза"
        VpnPhase.ERROR -> "ошибка"
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
            title = "Обновления",
            icon = AppIcons.Update,
            rows = listOf(
                {
                    SegmentAction(
                        title = "Проверить обновления",
                        subtitle = "Версия ${state.appVersion} · GitHub Releases",
                        onClick = { state.checkUpdates() },
                    )
                },
            ),
        )

        /* ---------------- Оформление ---------------- */

        SettingsGroup(
            title = "Оформление",
            icon = AppIcons.Moon,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = "Pitch Black",
                        subtitle = "Чёрный фон для OLED",
                        checked = settings.pitchBlack,
                        onCheckedChange = { state.saveSettings(settings.copy(pitchBlack = it)) },
                    )
                },
            ),
        )

        /* ---------------- Диагностика ---------------- */

        SettingsGroup(
            title = "Диагностика",
            icon = AppIcons.Tuning,
            rows = listOf(
                {
                    SegmentAction(
                        title = "Логи ядра",
                        subtitle = "Просмотр и копирование",
                        onClick = onOpenLogs,
                    )
                },
                {
                    SegmentAction(
                        title = "Проверить соединение",
                        subtitle = "Проверить выбранный узел",
                        onClick = { state.checkConnection() },
                    )
                },
                {
                    SegmentAction(
                        title = "О приложении",
                        subtitle = "Версия, ядро, исходники",
                        onClick = { showAbout = true },
                    )
                },
            ),
        )

        /* ---------------- Туннель ---------------- */

        SettingsGroup(
            title = "Туннель",
            icon = AppIcons.Global,
            rows = listOf(
                {
                    SegmentChoice(
                        title = "MTU",
                        subtitle = "Размер пакета",
                        options = listOf(1280, 1400, 1500).map { mtu ->
                            SegmentOption("$mtu", settings.mtu == mtu) {
                                state.saveSettings(settings.copy(mtu = mtu))
                            }
                        },
                    )
                },
                {
                    SegmentSwitch(
                        title = "IPv6 в туннеле",
                        subtitle = "IPv6 пойдёт мимо VPN",
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
                        label = "User-Agent подписки",
                    )
                },
            ),
        )

        /* ---------------- Обход блокировок ---------------- */

        SettingsGroup(
            title = "Обход блокировок",
            icon = AppIcons.Shield,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = "Фрагментация TLS",
                        subtitle = "Дробление ClientHello, обход DPI",
                        checked = settings.fragment,
                        onCheckedChange = { state.saveSettings(settings.copy(fragment = it)) },
                    )
                },
                {
                    SegmentSwitch(
                        title = "Российские домены напрямую",
                        subtitle = ".ru, .su, .рф — мимо туннеля",
                        checked = settings.bypassRu,
                        onCheckedChange = { state.saveSettings(settings.copy(bypassRu = it)) },
                    )
                },
            ),
        )

        /* ---------------- Раздельный туннель ---------------- */

        SettingsGroup(
            title = "Раздельный туннель",
            icon = AppIcons.Devices,
            rows = listOf(
                {
                    SegmentAction(
                        title = when (settings.splitMode) {
                            SplitMode.OFF -> "Выключен"
                            SplitMode.EXCLUDE -> "Все, кроме ${settings.splitPackages.size} прил."
                            SplitMode.INCLUDE -> "Только ${settings.splitPackages.size} прил."
                        },
                        subtitle = when (settings.splitMode) {
                            SplitMode.OFF -> "Весь трафик идёт в VPN"
                            else -> "Выбрать приложения"
                        },
                        onClick = onOpenSplit,
                    )
                },
            ),
        )

        /* ---------------- Подписка ---------------- */

        SettingsGroup(
            title = "Подписка",
            icon = AppIcons.Cloud,
            rows = listOf(
                {
                    SegmentAction(
                        title = if (state.subscriptions.isEmpty()) {
                            "Подписок нет"
                        } else {
                            "Подписок: ${state.subscriptions.size}"
                        },
                        subtitle = "Обновление, добавление, удаление",
                        onClick = onOpenSubscriptions,
                    )
                },
                {
                    SegmentChoice(
                        title = "Автообновление",
                        subtitle = "Как часто обновлять при запуске",
                        options = listOf(0, 6, 12, 24).map { hours ->
                            SegmentOption(
                                if (hours == 0) "выкл" else "${hours}ч",
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
            title = "Пинг",
            icon = AppIcons.Stopwatch,
            rows = listOf(
                {
                    SegmentChoice(
                        title = "Способ замера",
                        subtitle = state.pingMode.hint,
                        options = PingMode.entries.map { mode ->
                            SegmentOption(mode.label, state.pingMode == mode) {
                                state.choosePingMode(mode)
                            }
                        },
                    )
                },
            ),
        )

        /* ---------------- Уведомления ---------------- */

        SettingsGroup(
            title = "Уведомления",
            icon = AppIcons.Bell,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = "Показывать в шторке",
                        subtitle = "Состояние VPN и быстрые действия",
                        checked = settings.liveNotification,
                        onCheckedChange = { state.saveSettings(settings.copy(liveNotification = it)) },
                    )
                },
            ),
        )

        /* ---------------- Перенос ---------------- */

        SettingsGroup(
            title = "Перенос",
            icon = AppIcons.Qr,
            rows = listOf(
                {
                    SegmentAction(
                        title = "Принять с другого устройства",
                        subtitle = "QR-код для переноса подписок",
                        onClick = onOpenReceive,
                    )
                },
            ),
        )

        /* ---------------- Состояние ---------------- */

        SettingsGroup(
            title = "Состояние",
            icon = AppIcons.Info,
            rows = listOf(
                { SegmentInfo("Профилей", state.servers.size.toString()) },
                { SegmentInfo("Туннель", status.phase.label) },
                { SegmentInfo("Лог", "files/xray.log") },
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
                    Text("Версия ${state.appVersion}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Узлов: ${state.servers.size} · подписок: ${state.subscriptions.size}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Туннель: ${if (status.isConnected) "поднят" else "выключен"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAbout = false }) { Text("Закрыть") }
            },
            dismissButton = {
                TextButton(onClick = { state.openSource() }) { Text("Исходный код") }
            },
        )
    }

    state.update?.let { release ->
        AlertDialog(
            onDismissRequest = { state.dismissUpdate() },
            title = { Text("Доступно ${release.version}", fontWeight = FontWeight.Black) },
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
                ) { Text("Скачать и установить") }
            },
            dismissButton = {
                TextButton(onClick = { state.dismissUpdate() }) { Text("Позже") }
            },
        )
    }
}
