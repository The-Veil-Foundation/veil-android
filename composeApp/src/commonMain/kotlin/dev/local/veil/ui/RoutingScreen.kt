package dev.local.veil.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import dev.local.veil.core.CommunityCatalog
import dev.local.veil.core.CommunityCategory
import dev.local.veil.core.CommunityList
import dev.local.veil.core.DnsServerEntry
import dev.local.veil.core.GeoAssetSource
import dev.local.veil.core.DnsServerKind
import dev.local.veil.core.DnsSettings
import dev.local.veil.core.GroupKind
import dev.local.veil.core.RoutingPreset
import dev.local.veil.core.RoutingRule
import dev.local.veil.core.RuleDirection
import dev.local.veil.core.RuleTarget
import dev.local.veil.core.ServerGroup
import dev.local.veil.core.TargetKind
import dev.local.veil.core.newId
import dev.local.veil.platform.PlatformBackHandler

private enum class RoutingPage { HOME, RULES, COMMUNITY, DNS, GROUPS }

/**
 * Маршрутизация: пресет, правила, группы серверов и DNS.
 * Порт RoutingView / RulesView / DNSView / GroupsView из veil-ios.
 */
@Composable
fun RoutingScreen(
    state: VeilState,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(RoutingPage.HOME) }

    PlatformBackHandler(enabled = page != RoutingPage.HOME) { page = RoutingPage.HOME }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState.ordinal > initialState.ordinal) {
                (slideInHorizontally(tween(180)) { it / 3 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 4 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(180)) { -it / 3 } + fadeIn(tween(180))) togetherWith
                    (slideOutHorizontally(tween(180)) { it / 4 } + fadeOut(tween(150)))
            }
        },
        label = "routing-page",
    ) { current ->
        when (current) {
            RoutingPage.HOME -> RoutingHome(state, modifier, onOpen = { page = it })
            RoutingPage.RULES -> RulesPage(state, modifier, onBack = { page = RoutingPage.HOME })
            RoutingPage.COMMUNITY -> CommunityPage(state, modifier, onBack = { page = RoutingPage.HOME })
            RoutingPage.DNS -> DnsPage(state, modifier, onBack = { page = RoutingPage.HOME })
            RoutingPage.GROUPS -> GroupsPage(state, modifier, onBack = { page = RoutingPage.HOME })
        }
    }
}

/* ------------------------------------------------------------------ */
/* Главная                                                             */
/* ------------------------------------------------------------------ */

@Composable
private fun RoutingHome(
    state: VeilState,
    modifier: Modifier,
    onOpen: (RoutingPage) -> Unit,
) {
    val settings = state.settings
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsGroup(
            title = tr("Режим", "Mode"),
            icon = AppIcons.Routing,
            rows = listOf(
                {
                    SegmentChoice(
                        title = tr("Режим", "Mode"),
                        subtitle = routingPresetSubtitle(settings.routingPreset),
                        options = RoutingPreset.entries.map { preset ->
                            SegmentOption(routingPresetTitle(preset), settings.routingPreset == preset) {
                                state.saveRouting(preset, settings.customRules, settings.blockAds)
                            }
                        },
                    )
                },
                {
                    SegmentSwitch(
                        title = tr("Блокировать рекламу", "Block ads"),
                        subtitle = tr("geosite:category-ads-all (нужны geo-базы)", "geosite:category-ads-all (geo databases required)"),
                        checked = settings.blockAds,
                        onCheckedChange = {
                            state.saveRouting(settings.routingPreset, settings.customRules, it)
                        },
                    )
                },
            ),
        )

        SettingsGroup(
            title = tr("Правила", "Rules"),
            icon = AppIcons.Filter,
            rows = listOf(
                {
                    SegmentAction(
                        title = if (settings.customRules.isEmpty()) {
                            tr("Правил нет", "No rules")
                        } else {
                            tr("Правил: ${settings.customRules.size}", "Rules: ${settings.customRules.size}")
                        },
                        subtitle = tr("Домены, адреса, порты и приложения", "Domains, addresses, ports and apps"),
                        onClick = { onOpen(RoutingPage.RULES) },
                    )
                },
            ),
        )

        SettingsGroup(
            title = tr("Списки сообщества", "Community lists"),
            icon = AppIcons.Layers,
            rows = listOf(
                {
                    val selected = settings.communityLists.size
                    SegmentAction(
                        title = if (selected == 0) {
                            tr("Не выбраны", "None selected")
                        } else {
                            tr("Выбрано: $selected", "Selected: $selected")
                        },
                        subtitle = tr("Готовые домены и подсети", "Ready-made domains and subnets"),
                        onClick = { onOpen(RoutingPage.COMMUNITY) },
                    )
                },
            ),
        )

        SettingsGroup(
            title = tr("Группы серверов", "Server groups"),
            icon = AppIcons.Structure,
            rows = listOf(
                {
                    SegmentAction(
                        title = if (settings.groups.isEmpty()) {
                            tr("Групп нет", "No groups")
                        } else {
                            tr("Групп: ${settings.groups.size}", "Groups: ${settings.groups.size}")
                        },
                        subtitle = tr("Ручной выбор или самый быстрый узел", "Manual pick or the fastest server"),
                        onClick = { onOpen(RoutingPage.GROUPS) },
                    )
                },
            ),
        )

        SettingsGroup(
            title = "DNS",
            icon = AppIcons.Global,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = tr("Свой DNS", "Custom DNS"),
                        subtitle = if (settings.dnsSettings.enabled) {
                            tr("Серверов: ${settings.dnsSettings.servers.size} · ${settings.dnsSettings.strategy}", "Servers: ${settings.dnsSettings.servers.size} · ${settings.dnsSettings.strategy}")
                        } else {
                            tr("Использовать адрес из настроек туннеля", "Use the address from tunnel settings")
                        },
                        checked = settings.dnsSettings.enabled,
                        onCheckedChange = {
                            state.saveDns(settings.dnsSettings.copy(enabled = it))
                        },
                    )
                },
                {
                    SegmentAction(
                        title = tr("Серверы", "Servers"),
                        subtitle = tr("DoH, DoT, UDP и локальные имена", "DoH, DoT, UDP and local names"),
                        onClick = { onOpen(RoutingPage.DNS) },
                    )
                },
            ),
        )

        SettingsGroup(
            title = tr("База правил", "Rule database"),
            icon = AppIcons.Box,
            rows = listOf(
                {
                    SegmentChoice(
                        title = tr("Источник", "Source"),
                        subtitle = settings.geoSource.title,
                        options = GeoAssetSource.entries.map { source ->
                            SegmentOption(source.title, settings.geoSource == source) {
                                state.saveSettings(settings.copy(geoSource = source))
                            }
                        },
                    )
                },
                {
                    SegmentAction(
                        title = when {
                            state.geoBusy -> tr("Скачиваем…", "Downloading…")
                            state.geoAvailable -> tr("База загружена", "Database downloaded")
                            else -> tr("Скачать базу", "Download database")
                        },
                        subtitle = if (state.geoAvailable) {
                            tr("geosite:/geoip: работают", "geosite:/geoip: ready")
                        } else {
                            tr("Нужна для блокировки рекламы и geo-правил", "Required for ad blocking and geo rules")
                        },
                        onClick = { state.downloadGeo() },
                    )
                },
            ),
        )

        Spacer(Modifier.height(20.dp))
    }
}

/* ------------------------------------------------------------------ */
/* Готовые списки                                                      */
/* ------------------------------------------------------------------ */

private fun communityCategoryTitle(category: CommunityCategory): String = when (category) {
    CommunityCategory.REGION -> tr("Регионы", "Regions")
    CommunityCategory.CONTENT -> tr("Категории", "Categories")
    CommunityCategory.SERVICE -> tr("Сервисы", "Services")
}

@Composable
private fun CommunityPage(state: VeilState, modifier: Modifier, onBack: () -> Unit) {
    val settings = state.settings
    val selected = settings.communityLists
    val busy = state.communityBusy.isNotEmpty()
    val language = LocalLanguage.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHeader(
            title = tr("Списки", "Lists"),
            subtitle = tr("Готовые домены и подсети", "Ready-made domains and subnets"),
            onBack = onBack,
        )

        SettingsGroup(
            title = tr("Назначение", "Destination"),
            icon = AppIcons.Routing,
            rows = listOf(
                {
                    SegmentChoice(
                        title = tr("Куда отправлять совпадения", "Where matches go"),
                        subtitle = tr(
                            "Свои правила проверяются раньше, поэтому список не перекрывает их.",
                            "Your own rules are checked first, so a list never overrides them.",
                        ),
                        options = listOf(
                            tr("Прокси", "Proxy") to TargetKind.PROXY,
                            tr("Напрямую", "Direct") to TargetKind.DIRECT,
                            tr("Блок", "Block") to TargetKind.BLOCK,
                        ).map { (label, kind) ->
                            SegmentOption(label, settings.communityListTarget.kind == kind) {
                                state.setCommunityTarget(RuleTarget(kind))
                            }
                        },
                    )
                },
            ),
        )

        CommunityCategory.entries.forEach { category ->
            SettingsGroup(
                title = communityCategoryTitle(category),
                icon = when (category) {
                    CommunityCategory.REGION -> AppIcons.Global
                    CommunityCategory.CONTENT -> AppIcons.Filter
                    CommunityCategory.SERVICE -> AppIcons.Cloud
                },
                rows = CommunityCatalog.inCategory(category).map { list ->
                    {
                        CommunityRow(
                            list = list,
                            language = language,
                            checked = list.id in selected,
                            cache = state.communityCache[list.id],
                            downloading = list.id in state.communityBusy,
                            onCheckedChange = { state.toggleCommunityList(list.id) },
                        )
                    }
                },
            )
        }

        SettingsGroup(
            title = tr("Обновление", "Refresh"),
            icon = AppIcons.Refresh,
            rows = listOf(
                {
                    val subtitle = when {
                        busy -> tr("Скачиваем…", "Downloading…")
                        selected.isEmpty() -> tr("Сначала выберите список", "Select a list first")
                        else -> tr(
                            "Обновить выбранные списки",
                            "Refresh the selected lists",
                        )
                    }
                    SegmentAction(
                        title = tr("Обновить сейчас", "Update now"),
                        subtitle = subtitle,
                        onClick = { state.refreshCommunity() },
                        enabled = selected.isNotEmpty() && !busy,
                    )
                },
                {
                    SegmentAction(
                        title = tr("Очистить кэш", "Clear cache"),
                        subtitle = tr(
                            "Списки останутся выбранными и скачаются снова",
                            "Lists stay selected and will be downloaded again",
                        ),
                        onClick = { state.clearCommunityCache() },
                        enabled = state.communityCache.isNotEmpty(),
                    )
                },
            ),
        )

        state.communityError?.let { error ->
            EmptyHint(error)
        }

        Text(
            tr(
                "Списки из проекта allow-domains: выберите сервис или регион, и Veil сам добавит правило.",
                "Lists from the allow-domains project: pick a service or region and Veil adds the rule for you.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CommunityRow(
    list: CommunityList,
    language: dev.local.veil.core.AppLanguage,
    checked: Boolean,
    cache: VeilState.CommunityCache?,
    downloading: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val subtitle = when {
        downloading -> tr("Скачиваем…", "Downloading…")
        cache == null -> tr("Не загружен", "Not downloaded")
        else -> tr("${cache.entryCount} записей", "${cache.entryCount} entries")
    }
    SegmentSwitch(
        title = list.title(language),
        subtitle = subtitle,
        checked = checked,
        onCheckedChange = onCheckedChange,
    )
}

/* ------------------------------------------------------------------ */
/* Правила                                                             */
/* ------------------------------------------------------------------ */

@Composable
private fun RulesPage(state: VeilState, modifier: Modifier, onBack: () -> Unit) {
    val settings = state.settings
    var editing by remember { mutableStateOf<RoutingRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHeader(title = tr("Правила", "Rules"), subtitle = tr("Порядок важен: сверху вниз", "Order matters: top to bottom"), onBack = onBack)

        if (settings.customRules.isEmpty()) {
            EmptyHint(
                tr("Правил пока нет. Правило отправляет подходящий трафик напрямую, ", "There are no rules yet. A rule sends matching traffic direct, ")
                    + tr("в блокировку или в туннель.", "to the block list or to the tunnel."),
            )
        } else {
            SegmentedGroup(
                rows = settings.customRules.map { rule ->
                    {
                        RuleRow(
                            rule = rule,
                            onClick = { editing = rule },
                            onToggle = { enabled ->
                                state.saveRouting(
                                    settings.routingPreset,
                                    settings.customRules.map {
                                        if (it.id == rule.id) it.copy(enabled = enabled) else it
                                    },
                                    settings.blockAds,
                                )
                            },
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        AddButton(text = tr("Добавить правило", "Add rule")) { creating = true }
        Spacer(Modifier.height(20.dp))
    }

    if (creating || editing != null) {
        val rule = editing ?: RoutingRule()
        RuleDialog(
            initial = rule,
            isNew = creating,
            onDismiss = { creating = false; editing = null },
            onSave = { saved ->
                val next = settings.customRules
                    .filterNot { it.id == saved.id }
                    .let { list -> if (rule.id == saved.id && !creating) list else list }
                    .toMutableList()
                val index = settings.customRules.indexOfFirst { it.id == saved.id }
                if (index >= 0) next.add(index, saved) else next.add(saved)
                state.saveRouting(settings.routingPreset, next, settings.blockAds)
                creating = false
                editing = null
            },
            onDelete = {
                state.saveRouting(
                    settings.routingPreset,
                    settings.customRules.filterNot { it.id == rule.id },
                    settings.blockAds,
                )
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun RuleRow(rule: RoutingRule, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            onClick = onClick,
            color = androidx.compose.ui.graphics.Color.Transparent,
            modifier = Modifier.weight(1f),
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Text(
                    rule.name.ifBlank { tr("Без имени", "Untitled") },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    ruleSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

private fun ruleSummary(rule: RoutingRule): String {
    val dir = if (rule.direction == RuleDirection.SOURCE) {
        tr("источник", "source")
    } else {
        tr("назначение", "destination")
    }
    val matcher = when {
        rule.domains.isNotEmpty() -> rule.domains.take(2).joinToString(", ")
        rule.ips.isNotEmpty() -> rule.ips.take(2).joinToString(", ")
        rule.port.isNotBlank() -> tr("порт ${rule.port}", "port ${rule.port}")
        rule.network.isNotBlank() -> rule.network
        else -> tr("нет условий", "no conditions")
    }
    return "${ruleTargetTitle(rule.target)} · $dir · $matcher"
}

@Composable
private fun RuleDialog(
    initial: RoutingRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (RoutingRule) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var target by remember { mutableStateOf(initial.target) }
    var direction by remember { mutableStateOf(initial.direction) }
    var domains by remember { mutableStateOf(initial.domains.joinToString("\n")) }
    var ips by remember { mutableStateOf(initial.ips.joinToString("\n")) }
    var port by remember { mutableStateOf(initial.port) }
    var network by remember { mutableStateOf(initial.network) }
    var enabled by remember { mutableStateOf(initial.enabled) }

    EditorDialog(
        title = if (isNew) tr("Новое правило", "New rule") else tr("Правило", "Rule"),
        onDismiss = onDismiss,
        onDelete = if (isNew) null else onDelete,
        onSave = {
            onSave(
                initial.copy(
                    name = name.trim(),
                    target = target,
                    direction = direction,
                    domains = splitLines(domains),
                    ips = splitLines(ips),
                    port = port.trim(),
                    network = network,
                    enabled = enabled,
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(tr("Название", "Name")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ChipRow(
            title = tr("Куда", "Target"),
            options = listOf(
                tr("Прокси", "Proxy") to (target.kind == TargetKind.PROXY),
                tr("Напрямую", "Direct") to (target.kind == TargetKind.DIRECT),
                tr("Блок", "Block") to (target.kind == TargetKind.BLOCK),
            ),
        ) { index ->
            target = RuleTarget(
                when (index) {
                    0 -> TargetKind.PROXY
                    1 -> TargetKind.DIRECT
                    else -> TargetKind.BLOCK
                },
            )
        }
        ChipRow(
            title = tr("Матчить", "Match"),
            options = listOf(
                tr("Назначение", "Destination") to (direction == RuleDirection.DESTINATION),
                tr("Источник", "Source") to (direction == RuleDirection.SOURCE),
            ),
        ) { index ->
            direction = if (index == 0) RuleDirection.DESTINATION else RuleDirection.SOURCE
        }
        MultilineField(tr("Домены (по одному в строке)", "Domains (one per line)"), domains) { domains = it }
        MultilineField("IP / CIDR", ips) { ips = it }
        OutlinedTextField(
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
            title = tr("Состояние", "State"),
            options = listOf(tr("Включено", "On") to enabled, tr("Выключено", "Off") to !enabled),
        ) { index -> enabled = index == 0 }
    }
}

/* ------------------------------------------------------------------ */
/* DNS                                                                 */
/* ------------------------------------------------------------------ */

@Composable
private fun DnsPage(state: VeilState, modifier: Modifier, onBack: () -> Unit) {
    val dns = state.settings.dnsSettings
    var editing by remember { mutableStateOf<DnsServerEntry?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHeader(title = "DNS", subtitle = tr("Резолверы и стратегия", "Resolvers and strategy"), onBack = onBack)

        SettingsGroup(
            title = tr("Поведение", "Behavior"),
            icon = AppIcons.Global,
            rows = listOf(
                {
                    SegmentSwitch(
                        title = tr("Свой DNS", "Custom DNS"),
                        subtitle = tr("Иначе используется адрес из туннеля", "Otherwise the tunnel address is used"),
                        checked = dns.enabled,
                        onCheckedChange = { state.saveDns(dns.copy(enabled = it)) },
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Стратегия", "Strategy"),
                        subtitle = tr("Какое семейство адресов предпочитать", "Which address family to prefer"),
                        options = listOf(
                            "IPv4" to "prefer_ipv4",
                            "IPv6" to "prefer_ipv6",
                            tr("Только IPv4", "IPv4 only") to "ipv4_only",
                            tr("Только IPv6", "IPv6 only") to "ipv6_only",
                        ).map { (label, value) ->
                            SegmentOption(label, dns.strategy == value) {
                                state.saveDns(dns.copy(strategy = value))
                            }
                        },
                    )
                },
                {
                    SegmentChoice(
                        title = tr("Основной сервер", "Primary server"),
                        subtitle = tr("Отвечает, если правило не совпало", "Used when no rule matched"),
                        options = dns.servers
                            .filter { it.tag != "dns-bootstrap" }
                            .map { entry ->
                                SegmentOption(entry.tag, dns.finalTag == entry.tag) {
                                    state.saveDns(dns.copy(finalTag = entry.tag))
                                }
                            },
                    )
                },
            ),
        )

        SettingsGroup(
            title = tr("Серверы", "Servers"),
            icon = AppIcons.Server,
            rows = dns.servers.map { entry ->
                {
                    SegmentAction(
                        title = entry.tag,
                        subtitle = listOf(dnsKindTitle(entry.kind), entry.server)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        onClick = { editing = entry },
                    )
                }
            },
        )

        Spacer(Modifier.height(8.dp))
        AddButton(text = tr("Добавить сервер", "Add server")) { creating = true }
        Spacer(Modifier.height(20.dp))
    }

    if (creating || editing != null) {
        val entry = editing ?: DnsServerEntry(tag = "dns-${newId().take(4)}")
        DnsServerDialog(
            initial = entry,
            isNew = creating,
            onDismiss = { creating = false; editing = null },
            onSave = { saved ->
                val servers = dns.servers.toMutableList()
                val index = servers.indexOfFirst { it.id == saved.id }
                if (index >= 0) servers[index] = saved else servers += saved
                state.saveDns(dns.copy(servers = servers))
                creating = false
                editing = null
            },
            onDelete = {
                state.saveDns(dns.copy(servers = dns.servers.filterNot { it.id == entry.id }))
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun DnsServerDialog(
    initial: DnsServerEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (DnsServerEntry) -> Unit,
    onDelete: () -> Unit,
) {
    var tag by remember { mutableStateOf(initial.tag) }
    var kind by remember { mutableStateOf(initial.kind) }
    var server by remember { mutableStateOf(initial.server) }
    var port by remember { mutableStateOf(initial.port?.toString() ?: "") }
    var path by remember { mutableStateOf(initial.path) }

    EditorDialog(
        title = if (isNew) tr("Новый DNS", "New DNS") else tr("DNS-сервер", "DNS server"),
        onDismiss = onDismiss,
        onDelete = if (isNew) null else onDelete,
        onSave = {
            onSave(
                initial.copy(
                    tag = tag.trim().ifBlank { "dns" },
                    kind = kind,
                    server = server.trim(),
                    port = port.toIntOrNull(),
                    path = path.trim(),
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = tag,
            onValueChange = { tag = it },
            label = { Text(tr("Тег", "Tag")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ChipRow(
            title = tr("Тип", "Type"),
            options = DnsServerKind.entries.map { dnsKindTitle(it) to (kind == it) },
        ) { index -> kind = DnsServerKind.entries[index] }
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            label = { Text(tr("Адрес", "Address")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(tr("Порт", "Port")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text(tr("Путь DoH", "DoH path")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1.4f),
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Группы                                                              */
/* ------------------------------------------------------------------ */

@Composable
private fun GroupsPage(state: VeilState, modifier: Modifier, onBack: () -> Unit) {
    val settings = state.settings
    var editing by remember { mutableStateOf<ServerGroup?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHeader(title = tr("Группы", "Groups"), subtitle = tr("Один выход из нескольких узлов", "One route out of several servers"), onBack = onBack)

        if (settings.groups.isEmpty()) {
            EmptyHint(
                tr("Группа объединяет узлы: «ручной выбор» держит выбранный, ", "A group combines servers: “manual selection” keeps the chosen one, ")
                    + tr("«самый быстрый» замеряет и берёт лучший.", "“fastest” measures and picks the best."),
            )
        } else {
            SegmentedGroup(
                rows = settings.groups.map { group ->
                    {
                        SegmentAction(
                            title = group.name.ifBlank { tr("Без имени", "Untitled") },
                            subtitle = "${groupKindTitle(group.kind)} · ${group.members(state.servers).size} " + tr("узлов", "servers"),
                            onClick = { editing = group },
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        AddButton(text = tr("Добавить группу", "Add group")) { creating = true }
        Spacer(Modifier.height(20.dp))
    }

    if (creating || editing != null) {
        val group = editing ?: ServerGroup()
        GroupDialog(
            initial = group,
            isNew = creating,
            servers = state.servers,
            onDismiss = { creating = false; editing = null },
            onSave = { saved ->
                state.upsertGroup(saved)
                creating = false
                editing = null
            },
            onDelete = {
                state.removeGroup(group.id)
                creating = false
                editing = null
            },
        )
    }
}

@Composable
private fun GroupDialog(
    initial: ServerGroup,
    isNew: Boolean,
    servers: List<dev.local.veil.core.ProxyConfig>,
    onDismiss: () -> Unit,
    onSave: (ServerGroup) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var members by remember { mutableStateOf(initial.memberIds.toSet()) }

    EditorDialog(
        title = if (isNew) tr("Новая группа", "New group") else tr("Группа", "Group"),
        onDismiss = onDismiss,
        onDelete = if (isNew) null else onDelete,
        onSave = { onSave(initial.copy(name = name.trim(), kind = kind, memberIds = members.toList())) },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(tr("Название", "Name")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        ChipRow(
            title = tr("Тип", "Type"),
            options = GroupKind.entries.map { groupKindTitle(it) to (kind == it) },
        ) { index -> kind = GroupKind.entries[index] }
        Text(tr("Участники", "Members"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        servers.forEach { server ->
            val checked = server.id in members
            SegmentSwitch(
                title = server.name,
                subtitle = server.endpoint,
                checked = checked,
                onCheckedChange = {
                    members = if (it) members + server.id else members - server.id
                },
            )
        }
    }
}

/* ------------------------------------------------------------------ */
/* Общие части                                                         */
/* ------------------------------------------------------------------ */

@Composable
private fun PageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            onClick = onBack,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            AppIcon(AppIcons.ArrowLeft, modifier = Modifier.padding(10.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppIcon(AppIcons.Add, modifier = Modifier.padding(0.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.Black) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(tr("Сохранить", "Save")) } },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(tr("Удалить", "Delete"), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) }
            }
        },
    )
}

@Composable
private fun ChipRow(
    title: String,
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { index, (label, selected) ->
                ChipSmall(text = label, selected = selected, onClick = { onSelect(index) })
            }
        }
    }
}

@Composable
private fun MultilineField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun splitLines(raw: String): List<String> =
    raw.split('\n', ',', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
