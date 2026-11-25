package dev.local.veil.ui

import dev.local.veil.core.Pairing
import dev.local.veil.core.ShareLink
import dev.local.veil.core.TransferPayload
import dev.local.veil.platform.ReceiveSession
import dev.local.veil.platform.PingMode
import kotlinx.coroutines.Job
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.local.veil.core.AppSettings
import dev.local.veil.core.CommunityCatalog
import dev.local.veil.core.CommunityList
import dev.local.veil.core.DnsSettings
import dev.local.veil.core.GroupKind
import dev.local.veil.core.AddInput
import dev.local.veil.core.newId
import dev.local.veil.core.NodeAnnotation
import dev.local.veil.core.RoutingPreset
import dev.local.veil.core.RoutingRule
import dev.local.veil.core.RuleTarget
import dev.local.veil.core.ServerGroup
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.SplitMode
import dev.local.veil.core.Subscription
import dev.local.veil.core.TunnelOptions
import dev.local.veil.core.SubscriptionPayload
import dev.local.veil.core.dedupKey
import dev.local.veil.platform.AppEntry
import dev.local.veil.platform.AppContainer
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.SubscriptionFetch
import dev.local.veil.platform.SubscriptionUsage
import dev.local.veil.platform.VpnStatus
import dev.local.veil.platform.deleteFile
import dev.local.veil.platform.readTextFile
import dev.local.veil.platform.writeTextFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class VeilState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    val openNotificationSettings: (() -> Unit)? get() = container.openNotificationSettings

    var settings by mutableStateOf(container.settings.load().migrated())
        private set
    var servers by mutableStateOf<List<ProxyConfig>>(emptyList())
        private set
    var subscriptions by mutableStateOf<List<Subscription>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set

    data class CommunityCache(
        val domains: List<String> = emptyList(),
        val ips: List<String> = emptyList(),
        val updated: Long = 0L,
    ) {
        val isEmpty: Boolean get() = domains.isEmpty() && ips.isEmpty()
        val entryCount: Int get() = domains.size + ips.size
    }

    var communityCache by mutableStateOf<Map<String, CommunityCache>>(emptyMap())
        private set
    var communityBusy by mutableStateOf<Set<String>>(emptySet())
        private set
    var communityError by mutableStateOf<String?>(null)
        private set

    private val communityDirectory: String get() = "${container.geoDirectory}/community"

    init {

        container.subscriptions.userAgent = settings.userAgent
        scope.launch(Dispatchers.IO) {

            val loadedSettings = container.settings.load()
            val s = container.store.load()
            val subs = container.store.loadSubscriptions()
            val sel = container.store.loadSelectedId() ?: s.firstOrNull()?.id
            val cached = loadedSettings.communityLists.mapNotNull { id ->
                val list = CommunityCatalog.list(id) ?: return@mapNotNull null
                val domains = readTextFile("$communityDirectory/${list.id}.domains")
                    ?: return@mapNotNull null
                val ips = readTextFile("$communityDirectory/${list.id}.ips").orEmpty()
                val updated = readTextFile("$communityDirectory/${list.id}.time")
                    ?.trim()?.toLongOrNull() ?: 0L
                list.id to CommunityCache(
                    domains = domains.lineSequence().filter { it.isNotBlank() }.toList(),
                    ips = ips.lineSequence().filter { it.isNotBlank() }.toList(),
                    updated = updated,
                )
            }.toMap()
            withContext(Dispatchers.Main) {
                servers = s
                subscriptions = subs
                selectedId = sel
                communityCache = cached
            }
        }
    }
    var pings by mutableStateOf<Map<String, Int?>>(emptyMap())
        private set
    var pingMode by mutableStateOf(PingMode.REAL)
        private set

    var pingProgress by mutableStateOf(0 to 0)
        private set
    private var pingJob: Job? = null
    var pinging by mutableStateOf(false)

    var pingingIds by mutableStateOf<Set<String>>(emptySet())
        private set
    var busy by mutableStateOf(false)
        private set
    private var connectJob: kotlinx.coroutines.Job? = null
    private var switchJob: kotlinx.coroutines.Job? = null

    var checking by mutableStateOf(false)
        private set

    var checkingServer by mutableStateOf<ProxyConfig?>(null)
        private set

    var update by mutableStateOf<dev.local.veil.platform.AppRelease?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    val appVersion: String get() = container.update.currentVersion

    var bulletin by mutableStateOf<Bulletin?>(null)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    var geoAvailable by mutableStateOf(container.geo.hasAssets)
        private set
    var geoBusy by mutableStateOf(false)
        private set
    var geoProgress by mutableStateOf<dev.local.veil.platform.GeoDownloadProgress?>(null)
        private set
    var geoError by mutableStateOf<String?>(null)
        private set
    var routingError by mutableStateOf<String?>(null)
        private set
    private var routingApplyJob: Job? = null
    private var routingApplyPending = false

    fun applyRouting() {
        routingApplyPending = true
        if (routingApplyJob?.isActive == true) return
        routingApplyJob = scope.launch {
            while (routingApplyPending) {
                routingApplyPending = false
                kotlinx.coroutines.delay(400)
                switchJob?.join()
                connectJob?.join()

                kotlinx.coroutines.withTimeoutOrNull(30_000) {
                    while (container.vpn.status.value.isBusy) kotlinx.coroutines.delay(100)
                }
                if (!container.vpn.status.value.isConnected) return@launch
                val server = selected ?: return@launch
                routingError = null
                container.vpn.reconnect(server, tunnelOptions()).onFailure {
                    routingError = it.message ?: tr("Не удалось применить правила", "Could not apply rules")
                    showBulletin(routingError!!, BulletinKind.ERROR)
                }
            }
        }
    }
    var appsLoading by mutableStateOf(false)
        private set

    val activeGroup: ServerGroup?
        get() = settings.groups.firstOrNull { it.id == selectedId }

    val selected: ProxyConfig?
        get() {
            activeGroup?.representative(servers)?.let { return it }
            return servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()
        }

    val visibleServers: List<ProxyConfig>
        get() = servers.filter { settings.annotations[it.id]?.hidden != true }

    val orderedSubscriptions: List<Subscription>
        get() = subscriptions.filter { it.pinned } + subscriptions.filterNot { it.pinned }

    private fun tunnelOptions(logFile: String? = null): TunnelOptions =
        settings.toTunnelOptions(
            logFile = logFile,
            servers = servers,
            group = activeGroup,
            communityRules = communityRules(),
            geoAvailable = geoAvailable,
        )

    val coreAvailable: Boolean get() = container.vpn.coreAvailable

    var deviceId by mutableStateOf(container.subscriptions.deviceId)
        private set

    fun hwidFor(subscription: Subscription?): String? {
        val send = subscription?.sendHwid ?: settings.sendHwid
        if (!send) return null
        return subscription?.hwid?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: deviceId
    }

    fun regenerateDeviceId() {
        deviceId = container.subscriptions.regenerateDeviceId()
        showBulletin(tr("Новый ID устройства: $deviceId", "New device ID: $deviceId"), BulletinKind.SUCCESS)
    }

    fun setDeviceId(raw: String): Boolean {
        val value = container.subscriptions.setDeviceId(raw) ?: return false
        deviceId = value
        return true
    }
    val coreVersion: String? get() = container.vpn.coreVersion

    fun autoConnectOnLaunch(status: VpnStatus) {
        if (!settings.autoConnect) return
        if (selected == null) return
        if (status.phase != VpnPhase.DISCONNECTED && status.phase != VpnPhase.ERROR) return
        connect()
    }

    fun select(id: String, status: VpnStatus? = null) {
        val previous = selectedId
        selectedId = id
        container.store.saveSelectedId(id)

        if (status?.isConnected == true && previous != id) {
            val group = settings.groups.firstOrNull { it.id == id }
            val server = group?.representative(servers)
                ?: servers.firstOrNull { it.id == id }
                ?: return
            if (!server.isUsable) {
                showBulletin(tr("Протокол ${server.proto.wire} требует sing-box", "Protocol ${server.proto.wire} requires sing-box"), BulletinKind.ERROR)
                return
            }
            if (switchJob?.isActive == true) return
            switchJob = scope.launch {
                val options = tunnelOptions()

                val target = server
                container.vpn.reconnect(target, options).onFailure {

                    val back = servers.firstOrNull { it.id == previous }
                    if (back != null && back.id != target.id) {
                        selectedId = back.id
                        container.store.saveSelectedId(back.id)
                        container.vpn.reconnect(back, options)
                        showBulletin(tr("«${target.name}» не поднялся — вернули «${back.name}»", "“${target.name}” did not come up — reverted to “${back.name}”"), BulletinKind.ERROR)
                    } else {
                        showBulletin(it.message ?: tr("Не удалось переключить сервер", "Could not switch server"), BulletinKind.ERROR)
                    }
                }
            }
        }
    }

    val sharing get() = container.sharing

    fun saveSettings(next: AppSettings) {
        dev.local.veil.core.AppLog.event("Settings saved")
        val routingChanged = settings.routingFingerprint != next.routingFingerprint
        settings = next
        container.settings.save(next)
        container.subscriptions.userAgent = next.userAgent
        ensureGeo()
        if (routingChanged) applyRouting()
    }

    fun saveRouting(preset: RoutingPreset, rules: List<RoutingRule>, blockAds: Boolean) {
        saveSettings(settings.copy(routingPreset = preset, customRules = rules, blockAds = blockAds))
    }

    fun saveDns(dns: DnsSettings) {
        saveSettings(settings.copy(dnsSettings = dns))
    }

    fun moveSubscription(url: String, delta: Int) {
        val list = subscriptions.toMutableList()
        val index = list.indexOfFirst { it.url == url }
        val target = index + delta
        if (index < 0 || target !in list.indices) return
        val item = list.removeAt(index)
        list.add(target, item)
        subscriptions = list
        container.store.saveSubscriptions(list)
    }

    fun saveGroups(groups: List<ServerGroup>) {
        saveSettings(settings.copy(groups = groups))
    }

    fun upsertGroup(group: ServerGroup) {
        val next = settings.groups.toMutableList()
        val index = next.indexOfFirst { it.id == group.id }
        if (index >= 0) next[index] = group else next += group
        saveGroups(next)
    }

    fun selectGroupMember(groupId: String, serverId: String) {
        val group = settings.groups.firstOrNull { it.id == groupId } ?: return
        if (group.kind != GroupKind.SELECTOR) return
        upsertGroup(group.copy(selectedId = serverId))
        if (selectedId == groupId) {
            connect()
        }
    }

    fun pingGroup(group: ServerGroup) = pingTargets(group.members(servers))

    fun removeGroup(id: String) {
        saveGroups(settings.groups.filterNot { it.id == id })
        if (selectedId == id) {
            val fallback = servers.firstOrNull()?.id
            selectedId = fallback
            fallback?.let { container.store.saveSelectedId(it) }
        }
    }

    private val communityLanguage: dev.local.veil.core.AppLanguage
        get() = when (settings.language) {
            dev.local.veil.core.AppLanguage.SYSTEM -> systemLanguage()
            else -> settings.language
        }

    fun communityList(id: String): CommunityList? = CommunityCatalog.list(id)

    fun isCommunitySelected(id: String): Boolean = id in settings.communityLists

    fun toggleCommunityList(id: String) {
        val next = if (id in settings.communityLists) {
            settings.communityLists - id
        } else {
            settings.communityLists + id
        }
        saveSettings(settings.copy(communityLists = next))
        if (id in next && communityCache[id] == null) refreshCommunity(listOf(id))
    }

    fun setCommunityTarget(target: RuleTarget) {
        saveSettings(settings.copy(communityListTarget = target))
    }

    fun communityRules(): List<RoutingRule> = settings.communityLists.flatMap { id ->
        val list = CommunityCatalog.list(id) ?: return@flatMap emptyList()
        val cache = communityCache[id] ?: return@flatMap emptyList()
        dev.local.veil.core.communityRoutingRules(
            id = "community:$id",
            name = list.title(communityLanguage),
            target = settings.communityListTarget,
            domains = cache.domains,
            ips = cache.ips,
        )
    }

    fun refreshCommunity(ids: List<String> = settings.communityLists) {
        val todo = ids.filter { CommunityCatalog.list(it) != null }
        if (todo.isEmpty() || communityBusy.isNotEmpty()) return
        communityError = null
        communityBusy = todo.toSet()
        scope.launch {
            try {
            for (id in todo) {
                val list = CommunityCatalog.list(id) ?: continue
                val domainsText = fetchText(CommunityCatalog.rawUrl(list.domainPath))
                if (domainsText == null) {
                    communityError = tr("Не удалось скачать список", "Could not download the list")
                    continue
                }
                val domains = parseCommunityDomains(domainsText)
                var missingIpList = false
                val ips = buildList {
                    list.ipv4Path?.let { path ->
                        val text = fetchText(CommunityCatalog.rawUrl(path))
                        if (text == null) missingIpList = true else addAll(parseCommunityCidrs(text))
                    }
                    list.ipv6Path?.let { path ->
                        val text = fetchText(CommunityCatalog.rawUrl(path))
                        if (text == null) missingIpList = true else addAll(parseCommunityCidrs(text))
                    }
                }.distinct()
                if (missingIpList) {
                    communityError = tr("Не удалось скачать IP-часть списка. Сохранена предыдущая версия.",
                        "Could not download the IP list. The previous version was kept.")
                    continue
                }
                if (domains.isEmpty() && ips.isEmpty()) {
                    communityError = tr(
                        "Список «${list.title(communityLanguage)}» пришёл пустым",
                        "The “${list.title(communityLanguage)}” list came back empty",
                    )
                    continue
                }
                val entry = CommunityCache(domains, ips, currentTimeMillis())
                writeTextFile("$communityDirectory/$id.domains", domains.joinToString("\n"))
                writeTextFile("$communityDirectory/$id.ips", ips.joinToString("\n"))
                writeTextFile("$communityDirectory/$id.time", entry.updated.toString())
                communityCache = communityCache + (id to entry)
            }
            applyRouting()
            } finally { communityBusy = emptySet() }
        }
    }

    fun refreshCommunityDue() {
        val stale = settings.communityLists.filter { id ->
            val updated = communityCache[id]?.updated ?: 0L
            currentTimeMillis() - updated > 24 * 3_600_000L
        }
        refreshCommunity(stale)
    }

    fun clearCommunityCache() {
        CommunityCatalog.all.forEach { list ->
            deleteFile("$communityDirectory/${list.id}.domains")
            deleteFile("$communityDirectory/${list.id}.ips")
            deleteFile("$communityDirectory/${list.id}.time")
        }
        communityCache = emptyMap()
        applyRouting()
        showBulletin(
            tr("Кэш списков очищен", "List cache cleared"),
            BulletinKind.SUCCESS,
        )
    }

    private suspend fun fetchText(url: String): String? = withContext(Dispatchers.IO) {

        try { container.subscriptions.fetch(url, hwid = null).getOrThrow().body }
        catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: Exception) { null }
    }

    private fun parseCommunityDomains(text: String): List<String> = text.lineSequence()
        .mapNotNull { raw ->
            val value = raw.substringBefore('#').trim().trim('.').lowercase()
            if (value.isEmpty() || value.any { it.isWhitespace() }) null else value
        }
        .distinct()
        .toList()

    private fun parseCommunityCidrs(text: String): List<String> = text.lineSequence()
        .mapNotNull { raw ->
            val value = raw.substringBefore('#').trim()
            if (value.isEmpty()) return@mapNotNull null
            if (value.contains('/') || value.contains(':') || value.contains('.')) value else null
        }
        .distinct()
        .toList()

    fun annotationFor(serverId: String): NodeAnnotation =
        settings.annotations[serverId] ?: NodeAnnotation()

    fun displayName(server: ProxyConfig): String =
        annotationFor(server.id).nameOverride.ifBlank { server.name }

    fun saveAnnotation(serverId: String, annotation: NodeAnnotation) {
        val next = settings.annotations.toMutableMap()
        if (annotation == NodeAnnotation()) next.remove(serverId) else next[serverId] = annotation
        saveSettings(settings.copy(annotations = next))
    }

    fun setHidden(serverId: String, hidden: Boolean) {
        saveAnnotation(serverId, annotationFor(serverId).copy(hidden = hidden))
    }

    fun ensureGeo() {
        if (settings.needsGeoAssets && !geoAvailable && !geoBusy && !geoAutoFailed) downloadGeo()
    }

    private var geoAutoFailed = false

    fun downloadGeo() {
        if (geoBusy) return
        geoBusy = true
        geoError = null
        geoProgress = null
        geoAutoFailed = false
        val requested = settings
        scope.launch {
            try {
            container.geo.downloadWithProgress(requested.geoSource, requested.customGeoipUrl, requested.customGeositeUrl) { progress ->
                scope.launch { geoProgress = progress }
            }
                .onSuccess {
                    geoAvailable = container.geo.hasAssets
                    applyRouting()
                }
                .onFailure { error ->
                    geoAutoFailed = true
                    geoError = error.message ?: tr("Не удалось скачать базу правил", "Could not download the rule database")
                    showBulletin(geoError!!, BulletinKind.ERROR)
                }
            } finally {
                geoBusy = false
                geoAvailable = container.geo.hasAssets
            }
        }
    }

    fun dismissBulletin(id: Long? = null) {
        if (id == null || bulletin?.id == id) bulletin = null
    }

    private var bulletinSeq = 0L

    fun showBulletin(text: String, kind: BulletinKind = BulletinKind.INFO) {
        bulletin = Bulletin(++bulletinSeq, text, kind)
    }

    fun clearError() = container.vpn.clearError()

    fun choosePingMode(mode: PingMode) {
        if (pinging || mode == pingMode) return
        pingMode = mode
        pings = emptyMap()
    }

    fun cancelPing() {
        pingJob?.cancel()
        pinging = false
        pingingIds = emptySet()
    }

    fun pingAll() = pingTargets(visibleServers)

    fun pingSource(source: String?) = pingTargets(servers.filter { it.source == source })

    fun pingOne(server: ProxyConfig) = pingTargets(listOf(server))

    private fun pingTargets(targets: List<ProxyConfig>) {
        if (pinging || targets.isEmpty()) return
        val mode = pingMode
        if (mode == PingMode.REAL && !container.vpn.coreAvailable) {
            showBulletin(tr("Реальный пинг требует ядро в сборке — выберите TCP или ICMP", "Real ping requires a bundled core — choose TCP or ICMP"), BulletinKind.ERROR)
            return
        }
        pinging = true

        val ids = targets.map { it.id }.toSet()
        pingingIds = ids
        pings = pings.filterKeys { it !in ids }
        pingProgress = 0 to targets.size
        val options = tunnelOptions()

        pingJob = scope.launch {
            try {
                when (mode) {
                    PingMode.TCP, PingMode.ICMP -> {
                        val semaphore = Semaphore(8)
                        val results = targets.map { server ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    if (mode == PingMode.TCP) container.pinger.ping(server, timeoutMs = 1500)
                                    else container.pinger.icmp(server, timeoutMs = 2000)
                                }
                            }
                        }.awaitAll()
                        pings = pings + targets.mapIndexed { index, server -> server.id to results[index] }.toMap()
                        pingProgress = targets.size to targets.size
                    }
                    PingMode.REAL -> {

                        var done = 0
                        container.vpn.realPingBatch(targets, options, timeoutMs = 3000) { id, value ->
                            withContext(Dispatchers.Main) {
                                done++
                                pings = pings + (id to value)
                                pingingIds = pingingIds - id
                                pingProgress = done to targets.size
                            }
                        }
                    }
                }
            } finally {
                pinging = false
                pingingIds = emptySet()
            }
        }
    }

    fun loadApps() {
        if (apps.isNotEmpty() || appsLoading) return
        appsLoading = true
        scope.launch {
            apps = runCatching { container.packages.installedApps() }.getOrDefault(emptyList())
            appsLoading = false
        }
    }

    fun setSplitMode(mode: SplitMode) {
        saveSettings(settings.copy(splitMode = mode))
    }

    private val iconCache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()

    fun cachedIcon(packageName: String) = iconCache[packageName]

    suspend fun appIcon(packageName: String): androidx.compose.ui.graphics.ImageBitmap? {
        if (iconCache.containsKey(packageName)) return iconCache[packageName]
        val icon = runCatching { container.packages.icon(packageName) }.getOrNull()
        iconCache[packageName] = icon
        return icon
    }

    fun clearSplitPackages() {
        saveSettings(settings.copy(splitPackages = emptySet()))
    }

    private var splitSnapshot: Pair<SplitMode, Set<String>>? = null

    fun beginSplitEdit() {
        splitSnapshot = settings.splitMode to settings.splitPackages
    }

    fun finishSplitEdit() {
        val before = splitSnapshot ?: return
        splitSnapshot = null
        if (before == (settings.splitMode to settings.splitPackages)) return
        if (!container.vpn.status.value.isConnected) return
        val server = selected ?: return
        if (settings.splitMode == SplitMode.INCLUDE && settings.splitPackages.isEmpty()) {
            showBulletin(tr("Режим «Только»: выберите хотя бы одно приложение", "“Only” mode: select at least one app"), BulletinKind.ERROR)
            return
        }
        if (switchJob?.isActive == true) return
        showBulletin(tr("Применяем раздельный туннель…", "Applying split tunneling…"), BulletinKind.INFO)
        switchJob = scope.launch {
            container.vpn.reconnect(server, tunnelOptions()).onFailure {
                showBulletin(it.message ?: tr("Не удалось применить раздельный туннель", "Could not apply split tunneling"), BulletinKind.ERROR)
            }
        }
    }

    fun toggleSplitPackage(packageName: String) {
        val next = if (packageName in settings.splitPackages) {
            settings.splitPackages - packageName
        } else {
            settings.splitPackages + packageName
        }
        saveSettings(settings.copy(splitPackages = next))
    }

    suspend fun submitAdd(input: String, name: String = ""): String? {
        val text = input.trim()
        Pairing.parse(text)?.let {
            pendingPairing = it
            return null
        }
        return when (val kind = AddInput.classify(text)) {
            is AddInput.Kind.Subscription -> {
                busy = true
                bulletin = null
                val error = fetchAndAdd(kind.url, name.trim())
                busy = false
                error
            }
            is AddInput.Kind.Servers -> applyBody(kind.body, source = null, usage = null)
            is AddInput.Kind.Invalid -> tr("Это не ссылка и не адрес подписки", "Not a link or subscription URL")
            AddInput.Kind.Empty -> null
        }
    }

    fun addInput(input: String) {
        val text = input.trim()

        Pairing.parse(text)?.let {
            pendingPairing = it
            return
        }
        when (val kind = AddInput.classify(text)) {
            is AddInput.Kind.Subscription -> addSubscription(kind.url)
            is AddInput.Kind.Servers -> applyBody(kind.body, source = null, usage = null)
            else -> applyBody(SubscriptionPayload.classify(text), source = null, usage = null)
        }
    }

    private fun addSubscription(url: String) {
        scope.launch {
            busy = true
            bulletin = null
            fetchAndAdd(url)
            busy = false
        }
    }

    private suspend fun fetchAndAdd(url: String, name: String = ""): String? {
        val existing = subscriptions.firstOrNull { it.url == url }
        return container.subscriptions.fetch(url, existing?.userAgent, hwidFor(existing))
            .fold(
                onSuccess = { fetched ->
                    applyBody(
                        SubscriptionPayload.classify(fetched.body),
                        source = url,
                        usage = fetched.usage,
                        fetched = fetched,
                        name = name,
                    )
                },
                onFailure = {
                    val message = it.message ?: tr("Не удалось скачать подписку", "Could not download the subscription")
                    showBulletin(message, BulletinKind.ERROR)
                    message
                },
            )
    }

    fun updateSubscription(url: String, transform: (Subscription) -> Subscription) {
        subscriptions = subscriptions.map { if (it.url == url) transform(it) else it }
        container.store.saveSubscriptions(subscriptions)
    }

    fun duplicateToManual(server: ProxyConfig) {
        val copy = server.copy(id = newId(), source = null, name = server.name + tr(" (копия)", " (copy)"))
        servers = servers + copy
        container.store.save(servers)
        showBulletin(tr("Копия добавлена во «Вручную»", "Copy added to Manual"), BulletinKind.SUCCESS)
    }

    sealed interface NodeCheck {
        data object Ok : NodeCheck

        data class Failed(val message: String) : NodeCheck

        data object Unavailable : NodeCheck
    }

    suspend fun checkNode(node: ProxyConfig): NodeCheck {
        dev.local.veil.core.NodeRepresentation.missingCredential(node)?.let { (ru, en) ->
            return NodeCheck.Failed(tr(ru, en))
        }
        val engine = settings.coreEngine.resolve(node)
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val json = if (engine == dev.local.veil.core.CoreEngine.SING_BOX) {
                    dev.local.veil.core.SingboxConfig.build(node, TunnelOptions()).toString()
                } else {
                    dev.local.veil.core.XrayConfig.json(node, TunnelOptions())
                }
                container.checkConfig(engine, json)
            }.getOrElse { return@withContext Result.failure<Unit>(it) }
        } ?: return NodeCheck.Unavailable
        return result.fold(
            onSuccess = { NodeCheck.Ok },
            onFailure = { NodeCheck.Failed(it.message ?: it.toString()) },
        )
    }

    fun replaceServer(node: ProxyConfig) {
        servers = servers.map { if (it.id == node.id) node else it }
        container.store.save(servers)
    }

    val isTv: Boolean = container.transfer.isTelevision

    var pendingPairing by mutableStateOf<String?>(null)
        private set

    fun dismissTransfer() {
        pendingPairing = null
    }

    sealed interface TransferProgress {
        data class Sending(val subscriptions: Int, val servers: Int) : TransferProgress
        data class Receiving(val done: Int, val total: Int) : TransferProgress
        data class Finished(val ok: Boolean, val title: String, val message: String) : TransferProgress
    }

    var transferProgress by mutableStateOf<TransferProgress?>(null)
        private set

    fun closeTransferProgress() {
        if (transferProgress is TransferProgress.Finished) transferProgress = null
    }

    fun startReceiving(): ReceiveSession? = container.transfer.startReceiving { receiveTransfer(it) }

    fun receiveTransfer(payload: TransferPayload) {
        scope.launch {
            busy = true
            bulletin = null
            val before = servers.size
            val urls = payload.subscriptions.filter { it.startsWith("http://") || it.startsWith("https://") }
            transferProgress = TransferProgress.Receiving(done = 0, total = urls.size)
            var failed = 0
            urls.forEachIndexed { index, url ->
                if (fetchAndAdd(url) != null) failed++
                transferProgress = TransferProgress.Receiving(done = index + 1, total = urls.size)
            }
            if (payload.servers.isNotEmpty()) {
                applyBody(
                    SubscriptionPayload.classify(payload.servers.joinToString("\n")),
                    source = null,
                    usage = null,
                )
            }
            busy = false
            val added = servers.size - before
            transferProgress = TransferProgress.Finished(
                ok = failed < urls.size || added > 0,
                title = tr("Подписки получены", "Subscriptions received"),
                message = tr("Подписок: ${urls.size - failed}, новых серверов: $added.", "Subscriptions: ${urls.size - failed}, new servers: $added.") +
                    if (failed > 0) tr(" Не загрузилось: $failed — обновите их позже.", " Failed to load: $failed — refresh them later.") else "",
            )
        }
    }

    fun confirmTransfer(subscriptionUrls: Set<String>, manualServers: Boolean) {
        val address = pendingPairing ?: return
        pendingPairing = null
        val payload = TransferPayload(
            subscriptions = subscriptions.map { it.url }.filter { it in subscriptionUrls },
            servers = if (manualServers) servers.filter { it.source == null }.mapNotNull { ShareLink.encode(it) } else emptyList(),
        )
        if (payload.isEmpty) {
            showBulletin(tr("Нечего отправлять: добавьте подписку или сервер", "Nothing to send: add a subscription or server"), BulletinKind.ERROR)
            return
        }
        scope.launch {
            busy = true
            transferProgress = TransferProgress.Sending(payload.subscriptions.size, payload.servers.size)
            container.transfer.send(address, payload)
                .onSuccess {
                    transferProgress = TransferProgress.Finished(
                        ok = true,
                        title = tr("Отправлено", "Sent"),
                        message = tr(
                            "Подписок: ${payload.subscriptions.size}, серверов: ${payload.servers.size}. Приставка сейчас их загрузит — посмотрите на телевизор.",
                            "Subscriptions: ${payload.subscriptions.size}, servers: ${payload.servers.size}. The TV box is loading them now — check the TV.",
                        ),
                    )
                }
                .onFailure {
                    transferProgress = TransferProgress.Finished(
                        ok = false,
                        title = tr("Не удалось отправить", "Could not send"),
                        message = it.message ?: tr("Проверьте, что телефон и приставка в одной сети Wi-Fi.", "Make sure the phone and the TV box are on the same Wi-Fi."),
                    )
                }
            busy = false
        }
    }

    fun linkFor(server: ProxyConfig): String? = ShareLink.encode(server)

    fun share(text: String) = container.transfer.shareText(text)

    fun refreshSubscription(subscription: Subscription) {
        scope.launch {
            busy = true
            bulletin = null
            container.subscriptions.fetch(subscription.url, subscription.userAgent, hwidFor(subscription))
                .onSuccess { fetched ->
                    applyBody(
                        SubscriptionPayload.classify(fetched.body),
                        source = subscription.url,
                        replaceSource = true,
                        usage = fetched.usage,
                        fetched = fetched,
                    )
                    subscriptions = subscriptions.map {
                        if (it.url == subscription.url) {
                            it.copy(
                                lastFetched = currentTimeMillis(),
                                nodeCount = servers.count { s -> s.source == subscription.url },
                            )
                        } else {
                            it
                        }
                    }
                    container.store.saveSubscriptions(subscriptions)
                }
                .onFailure { showBulletin(it.message ?: tr("Подписка недоступна", "Subscription unavailable"), BulletinKind.ERROR) }
            busy = false
        }
    }

    fun refreshAll() {
        subscriptions.forEach { refreshSubscription(it) }
    }

    fun removeSubscription(subscription: Subscription) {
        saveSettings(settings.copy(groups = settings.groups.filterNot { it.source == subscription.url }))
        servers = servers.filterNot { it.source == subscription.url }
        subscriptions = subscriptions.filterNot { it.url == subscription.url }
        container.store.save(servers)
        container.store.saveSubscriptions(subscriptions)
        if (servers.none { it.id == selectedId }) {
            selectedId = servers.firstOrNull()?.id
            container.store.saveSelectedId(selectedId)
        }
    }

    private fun applyBody(
        body: SubscriptionPayload.Body,
        source: String?,
        replaceSource: Boolean = false,
        usage: SubscriptionUsage? = null,
        fetched: SubscriptionFetch? = null,
        name: String = "",
    ): String? {
        if (body is SubscriptionPayload.Body.Failure) {
            showBulletin(body.message, BulletinKind.ERROR)
            return body.message
        }
        body as SubscriptionPayload.Body.Ok

        val next = dev.local.veil.core.mergeImportedNodes(servers, body.nodes, source, replaceSource)
        val previousIds = servers.mapTo(HashSet()) { it.id }
        val added = next.count { it.id !in previousIds }

        servers = next
        container.store.save(next)
        if (body.format == SubscriptionPayload.Format.MIHOMO || (replaceSource && source != null)) {
            saveSettings(settings.copy(groups = dev.local.veil.core.mergeImportedGroups(
                settings.groups, body.groups, source, body.nodes, next,
            )))
        }

        if (source != null) {
            val existing = subscriptions.firstOrNull { it.url == source }
            fun clean(value: String?): String? =
                value?.trim()?.takeIf { it.isNotBlank() && !it.startsWith("base64:", ignoreCase = true) }
            val base0 = existing ?: Subscription(url = source)
            val entry = base0.copy(
                url = source,
                title = clean(fetched?.title)
                    ?: clean(existing?.title)
                    ?: source.substringAfter("://").substringBefore('/'),
                lastFetched = currentTimeMillis(),
                nodeCount = next.count { it.source == source },
                usedBytes = (usage?.used ?: existing?.usedBytes ?: 0L).takeIf { it > 0 } ?: 0L,
                totalBytes = usage?.total ?: existing?.totalBytes ?: 0L,
                expireAt = usage?.expireAt?.takeIf { it > 0 } ?: existing?.expireAt ?: 0L,
                description = clean(fetched?.description)
                    ?: clean(existing?.description).orEmpty(),
                supportUrl = fetched?.supportUrl?.takeIf { it.isNotBlank() }
                    ?: existing?.supportUrl.orEmpty(),
                webUrl = fetched?.webUrl?.takeIf { it.isNotBlank() }
                    ?: existing?.webUrl.orEmpty(),
                name = name.ifBlank { existing?.name.orEmpty() },
                lastFormat = body.format.label,
                skipped = body.skipped,
            )

            subscriptions = if (existing != null) {
                subscriptions.map { if (it.url == source) entry else it }
            } else {
                subscriptions + entry
            }
            container.store.saveSubscriptions(subscriptions)
        }

        if (next.none { it.id == selectedId }) {
            selectedId = (next.firstOrNull { !it.isPlaceholder } ?: next.firstOrNull())?.id
            container.store.saveSelectedId(selectedId)
        }

        showBulletin(when {
            added > 0 -> buildString {
                append(body.format.label).append(": +").append(added)
                if (body.skipped.isNotEmpty()) append(" · ").append(body.skipped.first())
            }
            source != null -> {
                val count = next.count { it.source == source }
                tr("Подписка обновлена · $count нод", "Subscription refreshed · $count servers")
            }
            else -> tr("Новых серверов нет — эти уже добавлены", "No new servers — these are already added")
        }, if (source != null) BulletinKind.SUCCESS else BulletinKind.INFO)
        busy = false
        return null
    }

    fun toggleTunnel(status: VpnStatus) {
        when (status.phase) {
            VpnPhase.CONNECTED -> scope.launch { container.vpn.disconnect() }
            VpnPhase.PAUSED -> connect()
            VpnPhase.CONNECTING -> Unit
            VpnPhase.DISCONNECTED, VpnPhase.ERROR -> connect()
        }
    }

    private fun connect() {
        dev.local.veil.core.AppLog.event("Connection requested")
        if (connectJob?.isActive == true) return
        val server = selected
        if (server == null) {
            showBulletin(tr("Сначала добавьте сервер", "Add a server first"), BulletinKind.ERROR)
            return
        }
        if (!server.isUsable) {
            showBulletin(tr("Протокол ${server.proto.wire} требует sing-box, а ядро sing-box недоступно", "Protocol ${server.proto.wire} requires sing-box, but the sing-box core is unavailable"), BulletinKind.ERROR)
            return
        }
        if (!coreAvailable) {
            showBulletin(tr("xray.aar не подключён к сборке", "xray.aar is not included in this build"), BulletinKind.ERROR)
            return
        }
        if (settings.splitMode == SplitMode.INCLUDE && settings.splitPackages.isEmpty()) {
            showBulletin(tr("Режим «Только»: выберите приложения в разделе «Раздельный туннель»", "“Only” mode: choose apps in “Split tunneling”"), BulletinKind.ERROR)
            return
        }
        bulletin = null
        connectJob = scope.launch {
            val options = tunnelOptions()

            val target = server
            container.vpn.connect(target, options).onFailure {
                showBulletin(it.message ?: tr("Не удалось подключиться", "Could not connect"), BulletinKind.ERROR)
            }
        }
    }

    fun checkConnection() {
        val server = selected
        if (server == null) {
            showBulletin(tr("Сначала выберите сервер", "Select a server first"), BulletinKind.ERROR)
            return
        }
        scope.launch {
            checking = true
            checkingServer = server
            val tcp: Int?
            val real: Int?
            try {
                tcp = withContext(Dispatchers.IO) { container.pinger.ping(server, timeoutMs = 4000) }
                real = if (container.vpn.coreAvailable) {
                    container.vpn.realPing(server, tunnelOptions(), timeoutMs = 6000)
                } else {
                    null
                }
            } finally {
                checking = false
                checkingServer = null
            }
            when {
                real != null -> {
                    if (pingMode == PingMode.REAL) pings = pings + (server.id to real)
                    val tcpPart = tcp?.let { tr(" · TCP $it мс", " · TCP $it ms") }.orEmpty()
                    showBulletin(tr("«${server.name}» отвечает · реальный $real мс$tcpPart", "“${server.name}” responds · real $real ms$tcpPart"), BulletinKind.SUCCESS)
                }
                tcp != null -> {
                    if (pingMode == PingMode.TCP) pings = pings + (server.id to tcp)
                    val note = if (container.vpn.coreAvailable) {
                        tr("порт открыт ($tcp мс), но через узел трафик не идёт", "port is open ($tcp ms), but traffic does not pass through the server")
                    } else {
                        tr("порт открыт · TCP $tcp мс", "port open · TCP $tcp ms")
                    }
                    showBulletin("«${server.name}»: $note", if (container.vpn.coreAvailable) BulletinKind.ERROR else BulletinKind.SUCCESS)
                }
                else -> showBulletin(tr("«${server.name}» не отвечает", "“${server.name}” is not responding"), BulletinKind.ERROR)
            }
        }
    }

    fun checkUpdates() {
        scope.launch {
            updateProgress = null
            container.update.latest()
                .onSuccess { release ->
                    if (release == null) {
                        showBulletin(tr("Обновлений нет · версия ${appVersion}", "No updates · version ${appVersion}"), BulletinKind.SUCCESS)
                    } else {
                        update = release
                    }
                }
                .onFailure {
                    val text = it.message ?: tr("Не удалось проверить обновления", "Could not check for updates")

                    val kind = if (text.contains("Релизов")) BulletinKind.INFO else BulletinKind.ERROR
                    showBulletin(text, kind)
                }
        }
    }

    fun downloadUpdate() {
        val release = update ?: return
        scope.launch {
            updateProgress = 0f
            container.update.download(release) { percent ->
                updateProgress = percent
            }
                .onSuccess { path ->
                    updateProgress = null
                    update = null
                    container.update.install(path)
                        .onSuccess { showBulletin(tr("Открываю установщик Android", "Opening the Android installer"), BulletinKind.INFO) }
                        .onFailure { showBulletin(it.message ?: tr("Не удалось запустить установку", "Could not start the installation"), BulletinKind.ERROR) }
                }
                .onFailure {
                    updateProgress = null
                    showBulletin(it.message ?: tr("Не удалось скачать обновление", "Could not download the update"), BulletinKind.ERROR)
                }
        }
    }

    fun dismissUpdate() {
        update = null
        updateProgress = null
    }

    fun openSource() = container.update.openSource()

    fun removeServer(id: String) {
        servers = servers.filterNot { it.id == id }
        container.store.save(servers)
        if (selectedId == id) {
            selectedId = servers.firstOrNull()?.id
            container.store.saveSelectedId(selectedId)
        }
    }
}

enum class BulletinKind { INFO, SUCCESS, ERROR }

data class Bulletin(val id: Long, val text: String, val kind: BulletinKind)

expect fun currentTimeMillis(): Long
