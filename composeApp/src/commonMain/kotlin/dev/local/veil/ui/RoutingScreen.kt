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
import androidx.compose.foundation.background
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
import dev.local.veil.core.DnsRule
import dev.local.veil.core.RuleSetRef
import dev.local.veil.core.GroupKind
import dev.local.veil.core.RoutingPreset
import dev.local.veil.core.RoutingRule
import dev.local.veil.core.RuleDirection
import dev.local.veil.core.RuleTarget
import dev.local.veil.core.ServerGroup
import dev.local.veil.core.TargetKind
import dev.local.veil.core.newId
import dev.local.veil.platform.PlatformBackHandler

private enum class RoutingPage { HOME, RULES, COMMUNITY, DNS, GROUPS, RULE_SETS }

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
            RoutingPage.RULE_SETS -> RuleSetsPage(state, modifier, onBack = { page = RoutingPage.HOME })
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

        CommunityFeature(state = state, onOpen = { onOpen(RoutingPage.COMMUNITY) })

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
            rows = buildList<@Composable () -> Unit> {
                addAll(listOf(
                {
                    SegmentChoice(
                        title = tr("Источник", "Source"),
                        subtitle = settings.geoSource.title,
                        options = GeoAssetSource.entries.map { source ->
                            SegmentOption(
                                if (source == GeoAssetSource.CUSTOM) tr("Свои URL", "Custom URLs") else source.title,
                                settings.geoSource == source,
                            ) {
                                state.saveSettings(settings.copy(geoSource = source))
                            }
                        },
                    )
                },
                ))
                if (settings.geoSource == GeoAssetSource.CUSTOM) {
                    addAll(listOf(
                    {
                        SegmentInput(
                            value = settings.customGeoipUrl,
                            onValueChange = { state.saveSettings(state.settings.copy(customGeoipUrl = it.trim())) },
                            label = "geoip.dat URL",
                        )
                    },
                    {
                        SegmentInput(
                            value = settings.customGeositeUrl,
                            onValueChange = { state.saveSettings(state.settings.copy(customGeositeUrl = it.trim())) },
                            label = "geosite.dat URL",
                        )
                    },
                    ))
                }
                addAll(listOf(
                {
                    SegmentAction(
                        title = if (settings.ruleSets.isEmpty()) {
                            "Rule-set"
                        } else {
                            "Rule-set: ${settings.ruleSets.size}"
                        },
                        subtitle = tr(
                            "Свои наборы для sing-box. geosite:/geoip: из правил подключаются сами.",
                            "Your own sing-box sets. geosite:/geoip: from rules are added automatically.",
                        ),
                        onClick = { onOpen(RoutingPage.RULE_SETS) },
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
                ))
            },
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
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        RuleTargetPicker(
                            title = tr("Куда отправлять совпадения", "Where matches go"),
                            target = settings.communityListTarget,
                            servers = state.servers,
                            groups = settings.groups,
                            onChange = { state.setCommunityTarget(it) },
                        )
                        Text(
                            tr(
                                "Свои правила проверяются раньше, поэтому список не перекрывает их.",
                                "Your own rules are checked first, so a list never overrides them.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    var creating by remember { mutableStateOf<RoutingRule?>(null) }
    var templates by remember { mutableStateOf(false) }

    fun save(rules: List<RoutingRule>) = state.saveRouting(settings.routingPreset, rules, settings.blockAds)

    fun move(id: String, delta: Int) {
        val list = settings.customRules.toMutableList()
        val index = list.indexOfFirst { it.id == id }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val item = list[index]
        list[index] = list[target]
        list[target] = item
        save(list)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHeader(
            title = tr("Правила", "Rules"),
            subtitle = tr("Сверху вниз, побеждает первое совпадение", "Top to bottom, first match wins"),
            onBack = onBack,
        )

        if (settings.customRules.isEmpty()) {
            EmptyHint(
                tr(
                    "Правил пока нет. Добавьте правило, чтобы пустить приложение, домен или сеть через нужный узел.",
                    "No rules yet. Add one to send an application, a domain or a network through a particular server.",
                ),
            )
        } else {
            SegmentedGroup(
                rows = settings.customRules.mapIndexed { index, rule ->
                    {
                        RuleRow(
                            rule = rule,
                            summary = ruleSummary(rule, state),
                            canMoveUp = index > 0,
                            canMoveDown = index < settings.customRules.lastIndex,
                            onClick = { editing = rule },
                            onMove = { delta -> move(rule.id, delta) },
                            onToggle = { enabled ->
                                save(settings.customRules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
                            },
                        )
                    }
                },
            )
        }
        Text(
            tr(
                "Правило срабатывает, когда совпали все заполненные поля. Домены: example.com, domain:example.com, keyword:google, geosite:netflix. IP: 1.2.3.0/24, geoip:cn.",
                "A rule matches when every filled-in field matches. Domains: example.com, domain:example.com, keyword:google, geosite:netflix. IPs: 1.2.3.0/24, geoip:cn.",
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(8.dp))
        AddButton(text = tr("Добавить правило", "Add rule")) { creating = RoutingRule() }
        AddButton(text = tr("Из шаблона", "From template")) { templates = true }
        Spacer(Modifier.height(20.dp))
    }

    if (templates) {
        AlertDialog(
            onDismissRequest = { templates = false },
            title = { Text(tr("Шаблоны", "Templates"), fontWeight = FontWeight.Black) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ruleTemplates.forEach { template ->
                        TextButton(onClick = {
                            templates = false
                            creating = template.rule()
                        }) { Text(template.title()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { templates = false }) { Text(tr("Отмена", "Cancel")) } },
        )
    }

    val open = creating ?: editing
    if (open != null) {
        val isNew = creating != null
        RuleDialog(
            state = state,
            initial = open,
            isNew = isNew,
            onDismiss = { creating = null; editing = null },
            onSave = { saved ->
                val index = settings.customRules.indexOfFirst { it.id == saved.id }
                val next = settings.customRules.toMutableList()
                if (index >= 0) next[index] = saved else next += saved
                save(next)
                creating = null
                editing = null
            },
            onDelete = {
                save(settings.customRules.filterNot { it.id == open.id })
                creating = null
                editing = null
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: RoutingRule,
    summary: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onClick: () -> Unit,
    onMove: (Int) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        androidx.compose.material3.IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            AppIcon(AppIcons.ChevronUp, modifier = Modifier.size(20.dp))
        }
        androidx.compose.material3.IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            AppIcon(AppIcons.ChevronDown, modifier = Modifier.size(20.dp))
        }
        androidx.compose.material3.Switch(checked = rule.enabled, onCheckedChange = onToggle)
    }
}

private fun ruleSummary(rule: RoutingRule, state: VeilState): String {
    val matcher = buildList {
        if (rule.processNames.isNotEmpty()) {
            add(rule.processNames.take(2).joinToString(", ") { pkg ->
                state.apps.firstOrNull { it.packageName == pkg }?.label ?: pkg.substringAfterLast('.')
            })
        }
        if (rule.domains.isNotEmpty()) add(rule.domains.take(2).joinToString(", "))
        if (rule.ips.isNotEmpty()) add(rule.ips.take(2).joinToString(", "))
        if (rule.port.isNotBlank()) add(tr("порт ${rule.port}", "port ${rule.port}"))
        if (rule.network.isNotBlank()) add(rule.network)
        if (rule.protocols.isNotEmpty()) add(rule.protocols.joinToString(", "))
        if (rule.ruleSets.isNotEmpty()) add(rule.ruleSets.joinToString(", "))
    }.joinToString(" · ").ifEmpty { tr("нет условий", "no conditions") }
    val target = ruleTargetLabel(rule.target, state.servers, state.settings.groups)
    val invert = if (rule.invert) tr("НЕ ", "NOT ") else ""
    return "→ $target · $invert$matcher"
}

/* ------------------------------------------------------------------ */
/* DNS                                                                 */
/* ------------------------------------------------------------------ */

@Composable
private fun DnsPage(state: VeilState, modifier: Modifier, onBack: () -> Unit) {
    val dns = state.settings.dnsSettings
    var editing by remember { mutableStateOf<DnsServerEntry?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editingRule by remember { mutableStateOf<DnsRule?>(null) }
    var creatingRule by remember { mutableStateOf(false) }

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

        SettingsGroup(
            title = tr("Правила DNS", "DNS rules"),
            icon = AppIcons.Filter,
            rows = if (dns.rules.isEmpty()) {
                listOf({ SegmentInfo(tr("Правил нет", "No rules"), "") })
            } else {
                dns.rules.map { rule ->
                    {
                        SegmentAction(
                            title = rule.name.ifBlank { rule.domains.firstOrNull() ?: tr("Без имени", "Untitled") },
                            subtitle = listOf(
                                if (rule.reject) tr("отклонить", "reject") else rule.serverTag,
                                rule.domains.take(2).joinToString(", "),
                                if (rule.enabled) "" else tr("выключено", "off"),
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                            onClick = { editingRule = rule },
                        )
                    }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        AddButton(text = tr("Добавить DNS-правило", "Add DNS rule")) { creatingRule = true }
        Spacer(Modifier.height(20.dp))
    }

    if (creatingRule || editingRule != null) {
        val rule = editingRule ?: DnsRule()
        DnsRuleDialog(
            state = state,
            initial = rule,
            serverTags = dns.servers.map { it.tag }.filter { it != "dns-bootstrap" },
            isNew = creatingRule,
            onDismiss = { creatingRule = false; editingRule = null },
            onSave = { saved ->
                val rules = dns.rules.toMutableList()
                val index = rules.indexOfFirst { it.id == saved.id }
                if (index >= 0) rules[index] = saved else rules += saved
                state.saveDns(dns.copy(rules = rules))
                creatingRule = false
                editingRule = null
            },
            onDelete = {
                state.saveDns(dns.copy(rules = dns.rules.filterNot { it.id == rule.id }))
                creatingRule = false
                editingRule = null
            },
        )
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
            colors = veilFieldColors(),
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
            colors = veilFieldColors(),
            value = server,
            onValueChange = { server = it },
            label = { Text(tr("Адрес", "Address")) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                colors = veilFieldColors(),
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(tr("Порт", "Port")) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                colors = veilFieldColors(),
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
            colors = veilFieldColors(),
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

/* ------------------------------------------------------------------ */
/* Rule-set                                                            */
/* ------------------------------------------------------------------ */

@Composable
private fun RuleSetsPage(state: VeilState, modifier: Modifier, onBack: () -> Unit) {
    val settings = state.settings
    var editing by remember { mutableStateOf<RuleSetRef?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PageHeader(
            title = "Rule-set",
            subtitle = tr("Наборы правил sing-box", "sing-box rule-sets"),
            onBack = onBack,
        )
        EmptyHint(
            tr(
                "geosite:/geoip: из правил превращаются в rule-set сами. Здесь — свои наборы; на них ссылаются по тегу в поле «Теги rule-set» правила.",
                "geosite:/geoip: entries become rule-sets on their own. Add your own here and refer to them by tag in a rule's Rule-set tags field.",
            ),
        )
        if (settings.ruleSets.isNotEmpty()) {
            SegmentedGroup(
                rows = settings.ruleSets.map { ref ->
                    {
                        SegmentAction(
                            title = ref.tag.ifBlank { tr("Без тега", "No tag") },
                            subtitle = if (ref.kind == RuleSetRef.Kind.REMOTE) ref.url else ref.path,
                            onClick = { editing = ref },
                        )
                    }
                },
            )
        }
        Spacer(Modifier.height(8.dp))
        AddButton(text = tr("Добавить rule-set", "Add rule-set")) { creating = true }
        Spacer(Modifier.height(20.dp))
    }

    if (creating || editing != null) {
        val ref = editing ?: RuleSetRef(tag = "")
        RuleSetDialog(
            initial = ref,
            isNew = creating,
            onDismiss = { creating = false; editing = null },
            onSave = { saved ->
                val list = settings.ruleSets.toMutableList()
                val index = list.indexOfFirst { it.id == saved.id }
                if (saved.tag.isNotBlank()) {
                    if (index >= 0) list[index] = saved else list += saved
                    state.saveSettings(settings.copy(ruleSets = list))
                }
                creating = false
                editing = null
            },
            onDelete = {
                state.saveSettings(settings.copy(ruleSets = settings.ruleSets.filterNot { it.id == ref.id }))
                creating = false
                editing = null
            },
        )
    }
}

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
        shape = MaterialTheme.shapes.large,
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
internal fun EditorDialog(
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
internal fun ChipRow(
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
internal fun MultilineField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        colors = veilFieldColors(),
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 2,
        maxLines = 4,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    )
}

internal fun splitLines(raw: String): List<String> =
    raw.split('\n', ',', ' ')
        .map { it.trim() }
        .filter { it.isNotEmpty() }

/* ------------------------------------------------------------------ */
/* Готовые списки — заметная карточка на главной                       */
/* ------------------------------------------------------------------ */

/** Популярные списки, которые можно включить прямо с главной. */
private val FEATURED_LISTS = listOf("youtube", "telegram", "discord", "ru-outside", "geoblock")

/**
 * Готовые списки — самый простой способ настроить маршрут: включил «YouTube»,
 * и его сайты пошли через VPN. Поэтому это не строка среди прочих, а
 * отдельная карточка с быстрыми переключателями и понятным описанием.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CommunityFeature(state: VeilState, onOpen: () -> Unit) {
    val settings = state.settings
    val scheme = MaterialTheme.colorScheme
    val selected = settings.communityLists
    val language = AppLocale.language
    val target = ruleTargetLabel(settings.communityListTarget, state.servers, settings.groups)

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = scheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(44.dp)
                        .background(scheme.primary, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AppIcon(AppIcons.Layers, modifier = Modifier.size(24.dp), tint = scheme.onPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Готовые списки", "Ready-made lists"),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = scheme.onPrimaryContainer,
                    )
                    Text(
                        if (selected.isEmpty()) tr("Ничего не включено", "Nothing enabled")
                        else tr("Включено: ${selected.size} · $target", "Enabled: ${selected.size} · $target"),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
            Text(
                tr(
                    "Включите сервис — и все его сайты пойдут так, как выбрано ниже, без ручных правил. Списки обновляются сами.",
                    "Turn on a service and all its sites follow the route below, no manual rules. Lists update themselves.",
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimaryContainer,
            )
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FEATURED_LISTS.mapNotNull { state.communityList(it) }.forEach { list ->
                    val on = list.id in selected
                    androidx.compose.material3.FilterChip(
                        selected = on,
                        onClick = { state.toggleCommunityList(list.id) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        leadingIcon = if (on) {
                            { AppIcon(AppIcons.Check, modifier = Modifier.size(16.dp)) }
                        } else null,
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
                            containerColor = scheme.surface.copy(alpha = 0.5f),
                            labelColor = scheme.onPrimaryContainer,
                            selectedContainerColor = scheme.primary,
                            selectedLabelColor = scheme.onPrimary,
                            selectedLeadingIconColor = scheme.onPrimary,
                        ),
                        border = null,
                        label = { Text(list.title(language)) },
                    )
                }
            }
            androidx.compose.material3.FilledTonalButton(
                onClick = onOpen,
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = scheme.surface.copy(alpha = 0.6f),
                    contentColor = scheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(tr("Все списки и куда направлять", "All lists and where to route"), fontWeight = FontWeight.Bold)
                Spacer(Modifier.size(6.dp))
                AppIcon(AppIcons.ChevronRight, modifier = Modifier.size(18.dp))
            }
        }
    }
}
