package dev.local.veil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.local.veil.core.SplitMode

/**
 * Выбор приложений: что пускать в туннель, а что мимо.
 *
 * Режим EXCLUDE — в VPN всё, кроме отмеченных; INCLUDE — только отмеченные.
 */
@Composable
fun SplitTunnelScreen(
    state: VeilState,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    val settings = state.settings

    LaunchedEffect(Unit) { state.loadApps() }

    val filtered = remember(state.apps, query) {
        if (query.isBlank()) state.apps
        else state.apps.filter {
            it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
        }
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SplitMode.entries.forEach { mode ->
                    ChipSmall(
                        text = when (mode) {
                            SplitMode.OFF -> "Выкл"
                            SplitMode.EXCLUDE -> "Кроме"
                            SplitMode.INCLUDE -> "Только"
                        },
                        selected = settings.splitMode == mode,
                        onClick = { state.setSplitMode(mode) },
                    )
                }
            }

            Text(
                text = when (settings.splitMode) {
                    SplitMode.OFF -> "Весь трафик идёт через VPN."
                    SplitMode.EXCLUDE -> "Отмеченные приложения пойдут мимо туннеля."
                    SplitMode.INCLUDE -> "Через туннель пойдут только отмеченные приложения."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                shape = RoundedCornerShape(999.dp),
                placeholder = { Text("Поиск приложения") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (settings.splitMode == SplitMode.OFF) {
                Text(
                    text = "Выберите «Кроме» или «Только», чтобы отметить приложения.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (state.appsLoading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val checked = app.packageName in settings.splitPackages
                    Surface(
                        onClick = { state.toggleSplitPackage(app.packageName) },
                        enabled = settings.splitMode != SplitMode.OFF,
                        shape = RoundedCornerShape(14.dp),
                        color = if (checked) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = settings.splitMode != SplitMode.OFF,
                            )
                            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                                Text(
                                    text = app.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (app.isSystem) {
                                Text(
                                    "системное",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
