package dev.local.veil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.local.veil.core.LogFilter
import dev.local.veil.core.LogLine
import dev.local.veil.core.LogSeverity
import dev.local.veil.platform.LogProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Чей лог на экране: рабочего туннеля или пробного ядра (пинги и проверки). */
private enum class LogSource { TUNNEL, CHECKS }

/**
 * Лог ядра, как панель логов на macOS: фильтр по тексту и по уровню,
 * счётчик «видно / всего», копируется то, что видно. Пока экран открыт, лог
 * подтягивается сам и идёт за концом, только если читатель и так внизу.
 */
@Composable
fun LogsScreen(
    provider: LogProvider,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onBulletin: (String) -> Unit = {},
) {
    var source by remember { mutableStateOf(LogSource.TUNNEL) }
    var raw by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var minimum by remember { mutableStateOf<LogSeverity?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    suspend fun read(from: LogSource): String =
        if (from == LogSource.TUNNEL) provider.read() else provider.readChecks()

    // Живой хвост: раз в секунду, пока экран на виду. Файл маленький
    // (последние 2000 строк), а строки разбираются только при изменении.
    LaunchedEffect(source) {
        raw = null
        while (true) {
            val next = read(source)
            if (next != raw) raw = next
            delay(1000)
        }
    }

    val all = remember(raw) { LogFilter.parse(raw.orEmpty()) }
    val shown = remember(all, query, minimum) { LogFilter.apply(all, query, minimum) }
    val filtering = query.isNotBlank() || minimum != null

    // Идём за концом, только если читатель уже внизу: прокрутка назад к
    // ошибке не должна сбиваться следующей строкой.
    val atEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || last >= info.totalItemsCount - 2
        }
    }
    var followed by remember { mutableStateOf(0) }
    LaunchedEffect(shown.size, source) {
        if (shown.isNotEmpty() && (atEnd || followed == 0)) {
            listState.scrollToItem(shown.lastIndex)
            followed++
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            LogSource.entries.forEachIndexed { index, entry ->
                SegmentedButton(
                    selected = source == entry,
                    onClick = {
                        source = entry
                        followed = 0
                    },
                    shape = SegmentedButtonDefaults.itemShape(index, LogSource.entries.size),
                    label = {
                        Text(
                            if (entry == LogSource.TUNNEL) tr("Туннель", "Tunnel") else tr("Проверки", "Checks"),
                        )
                    },
                )
            }
        }

        SearchPill(query = query, onQueryChange = { query = it })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LevelChip(tr("Все", "All"), minimum == null) { minimum = null }
                LevelChip("Info", minimum == LogSeverity.INFO) { minimum = LogSeverity.INFO }
                LevelChip("Warning", minimum == LogSeverity.WARNING) { minimum = LogSeverity.WARNING }
                LevelChip("Error", minimum == LogSeverity.ERROR) { minimum = LogSeverity.ERROR }
            }
            Text(
                if (filtering) "${shown.size}/${all.size}" else "${all.size}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            IconButton(
                enabled = shown.isNotEmpty(),
                onClick = {
                    // Копируется то, что видно: под фильтром тысяча скрытых
                    // строк — не то, о чём просили.
                    clipboard.setText(AnnotatedString(shown.joinToString("\n") { it.text }))
                    onBulletin(tr("Лог скопирован", "Log copied"))
                },
            ) { AppIcon(AppIcons.Copy, contentDescription = tr("Копировать", "Copy"), modifier = Modifier.size(20.dp)) }
            IconButton(
                enabled = all.isNotEmpty(),
                onClick = {
                    if (source == LogSource.TUNNEL) provider.clear() else provider.clearChecks()
                    scope.launch { raw = read(source) }
                    onBulletin(tr("Лог очищен", "Log cleared"))
                },
            ) { AppIcon(AppIcons.Trash, contentDescription = tr("Очистить", "Clear"), modifier = Modifier.size(20.dp)) }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth().weight(1f).padding(bottom = 12.dp),
        ) {
            when {
                raw == null -> Placeholder(tr("Читаю лог…", "Reading the log…"))
                all.isEmpty() -> Placeholder(
                    if (source == LogSource.TUNNEL) {
                        tr("Логов пока нет. Они появятся, когда туннель подключится.", "No logs yet. They appear once the tunnel connects.")
                    } else {
                        tr("Логов пока нет. Их пишет ядро, которое меряет пинг и проверяет узлы.", "No logs yet. The core that pings and checks servers writes them.")
                    },
                )
                shown.isEmpty() -> Placeholder(tr("Под этот фильтр ничего не подходит.", "Nothing matches this filter."))
                else -> SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp),
                    ) {
                        items(shown, key = { it.index }) { line -> LogRow(line) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LevelChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = CircleShape,
        label = { Text(label) },
    )
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Строка лога: полоска уровня слева, моноширинный текст, ошибки — цветом. */
@Composable
private fun LogRow(line: LogLine) {
    val scheme = MaterialTheme.colorScheme
    val accent = when (line.severity) {
        LogSeverity.ERROR, LogSeverity.FATAL -> scheme.error
        LogSeverity.WARNING -> scheme.tertiary
        else -> Color.Transparent
    }
    val color = when (line.severity) {
        LogSeverity.ERROR, LogSeverity.FATAL -> scheme.error
        LogSeverity.DEBUG -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp)) {
        Spacer(
            Modifier
                .padding(vertical = 3.dp)
                .width(3.dp)
                .height(14.dp)
                .background(accent, CircleShape),
        )
        Text(
            line.text,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            ),
            fontWeight = if (line.severity == LogSeverity.ERROR || line.severity == LogSeverity.FATAL) FontWeight.SemiBold else FontWeight.Normal,
            color = color,
            modifier = Modifier.padding(start = 8.dp, top = 1.dp, bottom = 1.dp),
        )
    }
}
