package dev.local.veil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.veil.core.SplitMode
import dev.local.veil.platform.VpnStatus

@Composable
fun SettingsScreen(
    state: VeilState,
    status: VpnStatus,
    onOpenSplit: () -> Unit,
    onOpenSubscriptions: () -> Unit,
    onOpenLogs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings = state.settings
    var showAbout by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GroupTitle("Оформление")

        SettingCard {
            SwitchRow(
                title = "Pitch Black",
                subtitle = "Чистый чёрный фон вместо тёмно-серого: экономит OLED и не слепит ночью",
                checked = settings.pitchBlack,
                onCheckedChange = { state.saveSettings(settings.copy(pitchBlack = it)) },
            )
        }

        GroupTitle("Диагностика")

        SettingCard {
            ActionRow(
                title = "Логи ядра",
                subtitle = "Открыть, скопировать или очистить лог Xray",
                onClick = onOpenLogs,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ActionRow(
                title = "Проверить соединение",
                subtitle = "TCP-проверка выбранного узла прямо сейчас",
                onClick = { state.checkConnection() },
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            ActionRow(
                title = "О приложении",
                subtitle = "Версия, ядро, число узлов и подписок",
                onClick = { showAbout = true },
            )
        }

        if (showAbout) {
            AlertDialog(
                onDismissRequest = { showAbout = false },
                title = { Text("Veil", fontWeight = FontWeight.Black) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Версия 0.1.0", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Ядро Xray ${status.coreVersion ?: state.coreVersion ?: "не подключено"}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
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
            )
        }

        GroupTitle("Туннель")

        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("MTU", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Размер пакета в туннеле", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1280, 1400, 1500).forEach { mtu ->
                        ChipSmall(
                            text = "$mtu",
                            selected = settings.mtu == mtu,
                            onClick = { state.saveSettings(settings.copy(mtu = mtu)) },
                        )
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            SwitchRow(
                title = "IPv6 в туннеле",
                subtitle = "Выключить — IPv6 пойдёт мимо VPN",
                checked = settings.ipv6,
                onCheckedChange = { state.saveSettings(settings.copy(ipv6 = it)) },
            )

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            OutlinedTextField(
                value = settings.dns.joinToString(", "),
                onValueChange = { raw ->
                    state.saveSettings(
                        settings.copy(dns = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
                    )
                },
                label = { Text("DNS") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = settings.userAgent,
                onValueChange = { state.saveSettings(settings.copy(userAgent = it)) },
                label = { Text("User-Agent подписки") },
                supportingText = { Text("Панель выбирает формат по этому заголовку") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        GroupTitle("Обход блокировок")
        SettingCard {
            SwitchRow(
                title = "Фрагментация TLS",
                subtitle = "Дробит ClientHello на пакеты: DPI теряет SNI и не рвёт рукопожатие",
                checked = settings.fragment,
                onCheckedChange = { state.saveSettings(settings.copy(fragment = it)) },
            )
            HorizontalDivider(Modifier.padding(vertical = 10.dp))
            SwitchRow(
                title = "Российские домены напрямую",
                subtitle = "Домены .ru, .su и .рф идут мимо туннеля — банки и госуслуги не ломаются",
                checked = settings.bypassRu,
                onCheckedChange = { state.saveSettings(settings.copy(bypassRu = it)) },
            )
        }

        GroupTitle("Раздельный туннель")
        NavigationRow(
            icon = Icons.Rounded.Route,
            title = when (settings.splitMode) {
                SplitMode.OFF -> "Выключен — весь трафик в VPN"
                SplitMode.EXCLUDE -> "Все, кроме ${settings.splitPackages.size} прил."
                SplitMode.INCLUDE -> "Только ${settings.splitPackages.size} прил."
            },
            subtitle = "Выбрать приложения",
            onClick = onOpenSplit,
        )

        GroupTitle("Подписка")
        NavigationRow(
            icon = Icons.Rounded.CloudSync,
            title = if (state.subscriptions.isEmpty()) "Подписок нет" else "Подписок: ${state.subscriptions.size}",
            subtitle = "Обновление, добавление, удаление",
            onClick = onOpenSubscriptions,
        )
        SettingCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Автообновление", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Как часто обновлять при запуске", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0, 6, 12, 24).forEach { hours ->
                        ChipSmall(
                            text = if (hours == 0) "выкл" else "${hours}ч",
                            selected = settings.autoRefreshHours == hours,
                            onClick = { state.saveSettings(settings.copy(autoRefreshHours = hours)) },
                        )
                    }
                }
            }
        }

        GroupTitle("Уведомления")
        SettingCard {
            SwitchRow(
                title = "Живое уведомление",
                subtitle = "Скорость и время в шторке, обновляется каждую секунду",
                checked = settings.liveNotification,
                onCheckedChange = { state.saveSettings(settings.copy(liveNotification = it)) },
                icon = Icons.Rounded.NotificationsActive,
            )
        }

        GroupTitle("Диагностика")
        SettingCard {
            InfoRow("Ядро", status.coreVersion?.let { "Xray $it" } ?: if (state.coreAvailable) "Xray" else "не подключено")
            InfoRow("Профилей", state.servers.size.toString())
            InfoRow("Состояние", status.phase.name.lowercase())
            InfoRow("Лог", "files/xray.log")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingCard(content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = androidx.compose.ui.graphics.Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipSmall(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
