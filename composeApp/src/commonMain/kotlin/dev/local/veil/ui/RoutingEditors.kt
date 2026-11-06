package dev.local.veil.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.RoutingPreset
import dev.local.veil.core.RoutingRule
import dev.local.veil.core.RuleDirection
import dev.local.veil.core.RuleTarget
import dev.local.veil.core.ServerGroup
import dev.local.veil.core.TargetKind
import dev.local.veil.core.singboxCompatible
import dev.local.veil.core.xrayCompatible

/* ------------------------------------------------------------------ */
/* Цель правила — порт RuleTargetPicker.swift                          */
/* ------------------------------------------------------------------ */

/** Подпись цели с именем узла или группы. */
internal fun ruleTargetLabel(target: RuleTarget, servers: List<ProxyConfig>, groups: List<ServerGroup>): String =
    when (target.kind) {
        TargetKind.SERVER -> servers.firstOrNull { it.id == target.id }?.name
            ?: tr("Узел удалён", "Missing server")
        TargetKind.GROUP -> groups.firstOrNull { it.id == target.id }?.name?.ifBlank { null }
            ?: if (groups.any { it.id == target.id }) tr("Группа", "Group") else tr("Группа удалена", "Missing group")
        else -> ruleTargetTitle(target)
    }

/**
 * Куда отправить совпадения: прокси, напрямую, блок, группа или конкретный
 * узел. Узлы выбираются в отдельном списке — их может быть сотни.
 */
@Composable
internal fun RuleTargetPicker(
    title: String,
    target: RuleTarget,
    servers: List<ProxyConfig>,
    groups: List<ServerGroup>,
    onChange: (RuleTarget) -> Unit,
) {
    var pickingServer by remember { mutableStateOf(false) }
    val options = buildList {
        add(tr("Прокси", "Proxy") to RuleTarget.Proxy)
        add(tr("Напрямую", "Direct") to RuleTarget.Direct)
        add(tr("Блок", "Block") to RuleTarget.Block)
        groups.forEach { group ->
            add(group.name.ifBlank { tr("Группа", "Group") } to RuleTarget.group(group.id))
        }
    }
    val serverLabel = if (target.kind == TargetKind.SERVER) {
        "${tr("Узел", "Server")}: ${ruleTargetLabel(target, servers, groups)}"
    } else {
        tr("Узел…", "Server…")
    }
    ChipRow(
        title = title,
        options = options.map { (label, value) -> label to (value == target) } +
            (serverLabel to (target.kind == TargetKind.SERVER)),
    ) { index ->
        if (index < options.size) onChange(options[index].second) else pickingServer = true
    }
    if (pickingServer) {
        ServerPickerDialog(
            servers = servers,
            selectedId = target.id.takeIf { target.kind == TargetKind.SERVER },
            onDismiss = { pickingServer = false },
            onPick = { id ->
                onChange(RuleTarget.server(id))
                pickingServer = false
            },
        )
    }
}

@Composable
private fun ServerPickerDialog(
    servers: List<ProxyConfig>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = servers.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Узел", "Server"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    colors = veilFieldColors(),
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Поиск", "Search")) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(filtered, key = { it.id }) { server ->
                        val engines = buildList {
                            if (server.xrayCompatible) add("Xray")
                            if (server.singboxCompatible) add("sing-box")
                        }.joinToString(" · ")
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                                Text(server.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(
                                    "${server.proto.wire} · $engines",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            ChipSmall(
                                text = if (server.id == selectedId) tr("Выбран", "Selected") else tr("Выбрать", "Pick"),
                                selected = server.id == selectedId,
                                onClick = { onPick(server.id) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

/* ------------------------------------------------------------------ */
/* Выбор приложений — порт ProcessPickerSheet.swift                    */
/* ------------------------------------------------------------------ */

@Composable
internal fun AppPickerDialog(
    state: VeilState,
    selection: List<String>,
    onDismiss: () -> Unit,
    onDone: (List<String>) -> Unit,
) {
    LaunchedEffect(Unit) { state.loadApps() }
    var picked by remember { mutableStateOf(selection.toSet()) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val apps = state.apps
        .filter { showSystem || !it.isSystem || it.packageName in picked }
        .filter {
            query.isBlank() || it.label.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
        }
        .sortedWith(compareByDescending<dev.local.veil.platform.AppEntry> { it.packageName in picked }.thenBy { it.label.lowercase() })

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Приложения", "Apps"), fontWeight = FontWeight.Black) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    colors = veilFieldColors(),
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(tr("Поиск", "Search")) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
                ChipSmall(
                    text = tr("Системные", "System apps"),
                    selected = showSystem,
                    onClick = { showSystem = !showSystem },
                )
                if (state.appsLoading) {
                    CircularProgressIndicator(Modifier.padding(16.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 360.dp)) {
                        items(apps, key = { it.packageName }) { app ->
                            val checked = app.packageName in picked
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        picked = if (checked) picked - app.packageName else picked + app.packageName
                                    },
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        app.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(picked.toList()) }) { Text(tr("Готово", "Done")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) } },
    )
}

/* ------------------------------------------------------------------ */
/* Редактор правила — порт RuleCard из RoutingSheet.swift              */
/* ------------------------------------------------------------------ */

private val SNIFFED_PROTOCOLS = listOf("http", "tls", "quic", "dns", "stun", "bittorrent", "dtls", "ssh", "rdp")

@Composable
internal fun RuleDialog(
    state: VeilState,
    initial: RoutingRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit,
    onDelete: () -> Unit,
) {
    val servers = state.servers
    val groups = state.settings.groups
    var name by remember { mutableStateOf(initial.name) }
    var target by remember { mutableStateOf(initial.target) }
    var direction by remember { mutableStateOf(initial.direction) }
    var domains by remember { mutableStateOf(initial.domains.joinToString("\n")) }
    var ips by remember { mutableStateOf(initial.ips.joinToString("\n")) }
    var apps by remember { mutableStateOf(initial.processNames) }
    var port by remember { mutableStateOf(initial.port) }
    var network by remember { mutableStateOf(initial.network) }
    var protocols by remember { mutableStateOf(initial.protocols) }
    var ruleSets by remember { mutableStateOf(initial.ruleSets.joinToString("\n")) }
    // Режим Clash на Android не переключается, поэтому в редакторе его нет;
    // значение из импортированного правила сохраняется как есть.
    val clashMode = initial.clashMode
    var invert by remember { mutableStateOf(initial.invert) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var showMore by remember {
        mutableStateOf(
            initial.port.isNotBlank() || initial.network.isNotBlank() || initial.protocols.isNotEmpty() ||
                initial.ruleSets.isNotEmpty() || initial.clashMode.isNotBlank() || initial.invert ||
                initial.direction == RuleDirection.SOURCE,
        )
    }
    var pickingApps by remember { mutableStateOf(false) }

    val draft = initial.copy(
        name = name.trim(),
        target = target,
        direction = direction,
        domains = splitLines(domains),
        ips = splitLines(ips),
        processNames = apps,
        port = port.trim(),
        network = network,
        protocols = protocols,
        ruleSets = splitLines(ruleSets),
        clashMode = clashMode,
        invert = invert,
        enabled = enabled,
    )

    EditorDialog(
        title = if (isNew) tr("Новое правило", "New rule") else tr("Правило", "Rule"),
        onDismiss = onDismiss,
        onDelete = if (isNew) null else onDelete,
        onSave = { onSave(draft) },
    ) {
        OutlinedTextField(
            colors = veilFieldColors(),
            value = name,
            onValueChange = { name = it },
            label = { Text(tr("Название", "Rule name")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        RuleTargetPicker(
            title = tr("Куда", "Target"),
            target = target,
            servers = servers,
            groups = groups,
            onChange = { target = it },
        )
        if (target.needsProfile) {
            RuleHint(
                tr(
                    "Узел или группа должны уметь работать на ядре активного узла. Иначе правило пойдёт через основной прокси.",
                    "The server or group must run on the active server's core. Otherwise the rule sends its traffic through the default proxy.",
                ),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("Приложения", "Apps"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                apps.forEach { pkg ->
                    val label = state.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg
                    ChipSmall(text = "$label  ✕", selected = true, onClick = { apps = apps - pkg })
                }
                ChipSmall(text = tr("Выбрать…", "Choose…"), selected = false, onClick = { pickingApps = true })
            }
            if (apps.isNotEmpty()) {
                RuleHint(
                    tr(
                        "Правила по приложениям выполняет ядро sing-box. Xray такие правила пропускает, а не расширяет.",
                        "Application matching is enforced by the sing-box core. The Xray path skips these rules rather than widening them.",
                    ),
                )
            }
        }

        MultilineField(tr("Домены: example.com, geosite:netflix", "Domains: example.com, geosite:netflix"), domains) { domains = it }
        MultilineField(tr("IP / CIDR: 1.2.3.0/24, geoip:cn", "IPs / CIDR: 1.2.3.0/24, geoip:cn"), ips) { ips = it }

        ChipRow(
            title = tr("Ещё", "More"),
            options = listOf(tr("Показать", "Show") to showMore),
        ) { showMore = !showMore }

        if (showMore) {
            OutlinedTextField(
                colors = veilFieldColors(),
                value = port,
                onValueChange = { port = it },
                label = { Text(tr("Порт: 443, 1000-2000", "Port: 443, 1000-2000")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            )
            ChipRow(
                title = tr("Сеть", "Network"),
                options = listOf(
                    tr("Любая", "Any") to network.isBlank(),
                    "TCP" to (network == "tcp"),
                    "UDP" to (network == "udp"),
                ),
            ) { index ->
                network = when (index) {
                    1 -> "tcp"
                    2 -> "udp"
                    else -> ""
                }
            }
            ChipRow(
                title = tr("Адреса — это", "Addresses are"),
                options = listOf(
                    tr("Назначение", "Destination") to (direction == RuleDirection.DESTINATION),
                    tr("Источник", "Source") to (direction == RuleDirection.SOURCE),
                ),
            ) { index ->
                direction = if (index == 0) RuleDirection.DESTINATION else RuleDirection.SOURCE
            }
            ChipRow(
                title = tr("Протоколы (сниффинг)", "Protocols (sniffed)"),
                options = SNIFFED_PROTOCOLS.map { it to (it in protocols) },
            ) { index ->
                val value = SNIFFED_PROTOCOLS[index]
                protocols = if (value in protocols) protocols - value else protocols + value
            }
            MultilineField(tr("Теги rule-set", "Rule-set tags"), ruleSets) { ruleSets = it }
            ChipRow(
                title = tr("Инвертировать", "Invert"),
                options = listOf(tr("Нет", "No") to !invert, tr("Да", "Yes") to invert),
            ) { index -> invert = index == 1 }
            if (protocols.isNotEmpty() || splitLines(ruleSets).isNotEmpty() || clashMode.isNotBlank() || invert) {
                RuleHint(
                    tr(
                        "Протоколы, rule-set и инверсию понимает только sing-box. На Xray правило пропускается.",
                        "Protocols, rule-sets and invert are sing-box only. The Xray path skips the rule.",
                    ),
                )
            }
        }

        ChipRow(
            title = tr("Состояние", "State"),
            options = listOf(tr("Включено", "On") to enabled, tr("Выключено", "Off") to !enabled),
        ) { index -> enabled = index == 0 }

        if (!draft.hasMatcher) {
            RuleHint(
                tr(
                    "Правило без условий ничего не матчит и пропускается.",
                    "A rule with no conditions matches nothing and is skipped.",
                ),
            )
        }
    }

    if (pickingApps) {
        AppPickerDialog(
            state = state,
            selection = apps,
            onDismiss = { pickingApps = false },
            onDone = { picked ->
                apps = picked
                pickingApps = false
            },
        )
    }
}

@Composable
internal fun RuleHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/* ------------------------------------------------------------------ */
/* Шаблоны — порт RuleTemplate                                         */
/* ------------------------------------------------------------------ */

internal data class RuleTemplate(val title: () -> String, val rule: () -> RoutingRule)

internal val ruleTemplates: List<RuleTemplate> = listOf(
    RuleTemplate({ tr("Приложение через узел", "An app through a server") }) { RoutingRule(name = "App") },
    RuleTemplate({ tr("Торренты напрямую", "Torrents direct") }) {
        RoutingRule(
            name = "Torrents",
            target = RuleTarget.Direct,
            processNames = listOf(
                "com.utorrent.client",
                "com.bittorrent.client",
                "org.proninyaroslav.libretorrent",
                "com.delphicoder.flud",
            ),
        )
    },
    RuleTemplate({ tr("Стриминг через прокси", "Streaming through the proxy") }) {
        RoutingRule(
            name = "Streaming",
            target = RuleTarget.Proxy,
            domains = listOf("geosite:netflix", "geosite:youtube", "geosite:disney", "geosite:spotify"),
        )
    },
    RuleTemplate({ tr("Блокировать QUIC (только TLS)", "Block QUIC (force TLS)") }) {
        RoutingRule(name = "Block QUIC", target = RuleTarget.Block, port = "443", network = "udp")
    },
    RuleTemplate({ tr("LAN напрямую", "LAN direct") }) {
        RoutingRule(name = "LAN direct", target = RuleTarget.Direct, ips = RoutingPreset.PRIVATE_CIDRS)
    },
)

/* ------------------------------------------------------------------ */
/* DNS-правило — порт DNSRule                                          */
/* ------------------------------------------------------------------ */

@Composable
internal fun DnsRuleDialog(
    state: VeilState,
    initial: dev.local.veil.core.DnsRule,
    serverTags: List<String>,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (dev.local.veil.core.DnsRule) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var serverTag by remember { mutableStateOf(initial.serverTag.ifBlank { serverTags.firstOrNull().orEmpty() }) }
    var domains by remember { mutableStateOf(initial.domains.joinToString("\n")) }
    var apps by remember { mutableStateOf(initial.processNames) }
    var ruleSets by remember { mutableStateOf(initial.ruleSets.joinToString("\n")) }
    var reject by remember { mutableStateOf(initial.reject) }
    var invert by remember { mutableStateOf(initial.invert) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var pickingApps by remember { mutableStateOf(false) }

    EditorDialog(
        title = if (isNew) tr("Новое DNS-правило", "New DNS rule") else tr("DNS-правило", "DNS rule"),
        onDismiss = onDismiss,
        onDelete = if (isNew) null else onDelete,
        onSave = {
            onSave(
                initial.copy(
                    name = name.trim(),
                    serverTag = serverTag,
                    domains = splitLines(domains),
                    processNames = apps,
                    ruleSets = splitLines(ruleSets),
                    reject = reject,
                    invert = invert,
                    enabled = enabled,
                ),
            )
        },
    ) {
        OutlinedTextField(
            colors = veilFieldColors(),
            value = name,
            onValueChange = { name = it },
            label = { Text(tr("Название", "Name")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ChipRow(
            title = tr("Ответ", "Answer"),
            options = listOf(tr("Через сервер", "Via server") to !reject, tr("Отклонить", "Reject") to reject),
        ) { index -> reject = index == 1 }
        if (!reject) {
            ChipRow(
                title = tr("Сервер", "Server"),
                options = serverTags.map { it to (it == serverTag) },
            ) { index -> serverTag = serverTags[index] }
        }
        MultilineField(tr("Домены: example.com, geosite:cn", "Domains: example.com, geosite:cn"), domains) { domains = it }
        ChipRow(
            title = tr("Приложения", "Apps"),
            options = apps.map { pkg -> (state.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg) + "  ✕" to true } +
                (tr("Выбрать…", "Choose…") to false),
        ) { index -> if (index < apps.size) apps = apps - apps[index] else pickingApps = true }
        MultilineField(tr("Теги rule-set", "Rule-set tags"), ruleSets) { ruleSets = it }
        ChipRow(
            title = tr("Инвертировать", "Invert"),
            options = listOf(tr("Нет", "No") to !invert, tr("Да", "Yes") to invert),
        ) { index -> invert = index == 1 }
        ChipRow(
            title = tr("Состояние", "State"),
            options = listOf(tr("Включено", "On") to enabled, tr("Выключено", "Off") to !enabled),
        ) { index -> enabled = index == 0 }
    }

    if (pickingApps) {
        AppPickerDialog(
            state = state,
            selection = apps,
            onDismiss = { pickingApps = false },
            onDone = { picked ->
                apps = picked
                pickingApps = false
            },
        )
    }
}
