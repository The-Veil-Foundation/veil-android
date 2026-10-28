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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.veil.platform.LogProvider
import kotlinx.coroutines.launch

/** Просмотр лога ядра: обновить, скопировать, очистить. */
@Composable
fun LogsScreen(
    provider: LogProvider,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onBulletin: (String) -> Unit = {},
) {
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    suspend fun reload() {
        loading = true
        lines = provider.read().lines()
        loading = false
    }

    LaunchedEffect(provider) { reload() }

    // Свежие записи внизу — туда и прокручиваем.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = {
                    scope.launch {
                        reload()
                        onBulletin("Лог обновлён")
                    }
                },
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Обновить")
            }
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(lines.joinToString("\n")))
                    onBulletin("Лог скопирован в буфер обмена")
                },
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Копировать")
            }
            TextButton(
                onClick = {
                    provider.clear()
                    scope.launch {
                        reload()
                        onBulletin("Лог очищен")
                    }
                },
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(" Очистить")
            }
        }

        Text(
            text = provider.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        ) {
            if (loading) {
                Text(
                    "Читаю лог…",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(lines) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                            ),
                            fontWeight = if (line.contains("[Error]") || line.contains("[Warning]")) {
                                FontWeight.Bold
                            } else {
                                FontWeight.Normal
                            },
                            color = if (line.contains("[Error]")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}
