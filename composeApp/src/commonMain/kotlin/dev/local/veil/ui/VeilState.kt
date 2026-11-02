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
import dev.local.veil.core.ProxyConfig
import dev.local.veil.core.SplitMode
import dev.local.veil.core.Subscription
import dev.local.veil.core.SubscriptionPayload
import dev.local.veil.core.dedupKey
import dev.local.veil.platform.AppEntry
import dev.local.veil.platform.AppContainer
import dev.local.veil.platform.VpnPhase
import dev.local.veil.platform.SubscriptionUsage
import dev.local.veil.platform.VpnStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class VeilState(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) {
    // Ленивая загрузка для ускорения старта: серверы/подписки грузим в фоне.
    var settings by mutableStateOf(container.settings.load())
        private set
    var servers by mutableStateOf<List<ProxyConfig>>(emptyList())
        private set
    var subscriptions by mutableStateOf<List<Subscription>>(emptyList())
        private set
    var selectedId by mutableStateOf<String?>(null)
        private set
    
    init {
        scope.launch(Dispatchers.IO) {
            val s = container.store.load()
            val subs = container.store.loadSubscriptions()
            val sel = container.store.loadSelectedId() ?: s.firstOrNull()?.id
            withContext(Dispatchers.Main) {
                servers = s
                subscriptions = subs
                selectedId = sel
            }
        }
    }
    var pings by mutableStateOf<Map<String, Int?>>(emptyMap())
        private set
    var pingMode by mutableStateOf(PingMode.REAL)
        private set
    /** Сколько серверов уже измерено из скольких — для индикатора. */
    var pingProgress by mutableStateOf(0 to 0)
        private set
    private var pingJob: Job? = null
    var pinging by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var connectJob: kotlinx.coroutines.Job? = null
    private var switchJob: kotlinx.coroutines.Job? = null

    /** Идёт проверка узла перед подключением. */
    var checking by mutableStateOf(false)
        private set
    /** Какой узел проверяем — для подписи в статус-карточке. */
    var checkingServer by mutableStateOf<ProxyConfig?>(null)
        private set
    /** Доступное обновление и прогресс его загрузки. */
    var update by mutableStateOf<dev.local.veil.platform.AppRelease?>(null)
        private set
    var updateProgress by mutableStateOf<Float?>(null)
        private set
    val appVersion: String get() = container.update.currentVersion

    /** Всплывающее сообщение внизу экрана (тост-буллетин). */
    var bulletin by mutableStateOf<Bulletin?>(null)
        private set
    var apps by mutableStateOf<List<AppEntry>>(emptyList())
        private set
    var appsLoading by mutableStateOf(false)
        private set

    val selected: ProxyConfig?
        get() = servers.firstOrNull { it.id == selectedId } ?: servers.firstOrNull()

    val coreAvailable: Boolean get() = container.vpn.coreAvailable
    val coreVersion: String? get() = container.vpn.coreVersion

    /* ---------------- выбор и настройки ---------------- */

    fun select(id: String, status: VpnStatus? = null) {
        val previous = selectedId
        selectedId = id
        container.store.saveSelectedId(id)

        if (status?.isConnected == true && previous != id) {
            val server = servers.firstOrNull { it.id == id } ?: return
            if (!server.isUsable) {
                showBulletin("Протокол ${server.proto.wire} требует sing-box", BulletinKind.ERROR)
                return
            }
            if (switchJob?.isActive == true) return
            switchJob = scope.launch {
                val options = settings.toTunnelOptions()
                // Полная проверка идёт во втором экземпляре ядра, поэтому
                // рабочий туннель и Телеграм не замечают её.
                val target = pickWorkingNode(server, options)
                if (target == null) {
                    // Ни один узел не подтвердился — возвращаемся на прежний,
                    // чтобы не оставить пользователя с пустым туннелем.
                    val back = servers.firstOrNull { it.id == previous }
                    showBulletin("«${server.name}» не отвечает — вернули «${back?.name ?: "прежний"}»", BulletinKind.ERROR)
                    if (back != null) {
                        selectedId = back.id
                        container.store.saveSelectedId(back.id)
                        container.vpn.reconnect(back, options)
                    }
                    return@launch
                }
                if (target.id != server.id) {
                    selectedId = target.id
                    container.store.saveSelectedId(target.id)
                    showBulletin("«${server.name}» не отвечает — переключились на «${target.name}»", BulletinKind.INFO)
                }
                container.vpn.reconnect(target, options).onFailure {
                    // Если новый узел не поднялся — возвращаемся на прежний,
                    // чтобы не оставить пользователя без туннеля.
                    val back = servers.firstOrNull { it.id == previous }
                    if (back != null && back.id != target.id) {
                        selectedId = back.id
                        container.store.saveSelectedId(back.id)
                        container.vpn.reconnect(back, options)
                        showBulletin("«${target.name}» не поднялся — вернули «${back.name}»", BulletinKind.ERROR)
                    } else {
                        showBulletin(it.message ?: "Не удалось переключить сервер", BulletinKind.ERROR)
                    }
                }
            }
        }
    }

    fun saveSettings(next: AppSettings) {
        settings = next
        container.settings.save(next)
        container.subscriptions.userAgent = next.userAgent
    }

    fun dismissBulletin(id: Long? = null) {
        if (id == null || bulletin?.id == id) bulletin = null
    }

    private var bulletinSeq = 0L

    fun showBulletin(text: String, kind: BulletinKind = BulletinKind.INFO) {
        bulletin = Bulletin(++bulletinSeq, text, kind)
    }

    fun clearError() = container.vpn.clearError()

    /* ---------------- пинг ---------------- */

    fun choosePingMode(mode: PingMode) {
        if (pinging || mode == pingMode) return
        pingMode = mode
        pings = emptyMap() // цифры разных методов нельзя смешивать
    }

    fun cancelPing() {
        pingJob?.cancel()
    }

    fun pingAll() = pingTargets(servers)

    /** Пинг одной подписки (или группы «Вручную», если [source] == null). */
    fun pingSource(source: String?) = pingTargets(servers.filter { it.source == source })

    fun pingOne(server: ProxyConfig) = pingTargets(listOf(server))

    private fun pingTargets(targets: List<ProxyConfig>) {
        if (pinging || targets.isEmpty()) return
        val mode = pingMode
        if (mode == PingMode.REAL && !container.vpn.coreAvailable) {
            showBulletin("Реальный пинг требует ядро Xray — выберите TCP или ICMP", BulletinKind.ERROR)
            return
        }
        pinging = true
        // Старые цифры других групп остаются: замер идёт только по выбранным.
        val ids = targets.map { it.id }.toSet()
        pings = pings.filterKeys { it !in ids }
        pingProgress = 0 to targets.size
        val options = settings.toTunnelOptions()

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
                        // Ядро для замера одно, поэтому по очереди; результаты
                        // появляются сразу, а не в конце.
                        targets.forEachIndexed { index, server ->
                            val value = container.vpn.realPing(server, options, timeoutMs = 3500)
                            pings = pings + (server.id to value)
                            pingProgress = (index + 1) to targets.size
                        }
                    }
                }
            } finally {
                pinging = false
            }
        }
    }

    /* ---------------- раздельный туннель ---------------- */

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

    /** Экран выбора открыт: запоминаем исходное состояние, чтобы понять, менялось ли оно. */
    fun beginSplitEdit() {
        splitSnapshot = settings.splitMode to settings.splitPackages
    }

    /**
     * Экран закрыт. TUN создаётся один раз при подключении, поэтому изменения
     * применяются только пересозданием интерфейса: раньше их приходилось
     * подтверждать ручным переподключением.
     */
    fun finishSplitEdit() {
        val before = splitSnapshot ?: return
        splitSnapshot = null
        if (before == (settings.splitMode to settings.splitPackages)) return
        if (!container.vpn.status.value.isConnected) return
        val server = selected ?: return
        if (settings.splitMode == SplitMode.INCLUDE && settings.splitPackages.isEmpty()) {
            showBulletin("Режим «Только»: выберите хотя бы одно приложение", BulletinKind.ERROR)
            return
        }
        if (switchJob?.isActive == true) return
        showBulletin("Применяем раздельный туннель…", BulletinKind.INFO)
        switchJob = scope.launch {
            container.vpn.reconnect(server, settings.toTunnelOptions()).onFailure {
                showBulletin(it.message ?: "Не удалось применить раздельный туннель", BulletinKind.ERROR)
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

    /* ---------------- подписки ---------------- */

    /**
     * Один вход: ссылка на подписку, тело подписки, ссылки нод или wg-conf.
     */
    fun addInput(input: String) {
        val text = input.trim()
        // QR приставки: не подписка, а предложение отправить подписки на неё.
        Pairing.parse(text)?.let {
            pendingPairing = it
            return
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            addSubscription(text)
            return
        }
        applyBody(SubscriptionPayload.classify(text), source = null, usage = null)
    }

    private fun addSubscription(url: String) {
        scope.launch {
            busy = true
            bulletin = null
            fetchAndAdd(url)
            busy = false
        }
    }

    private suspend fun fetchAndAdd(url: String) {
        container.subscriptions.fetch(url)
            .onSuccess { fetched ->
                applyBody(SubscriptionPayload.classify(fetched.body), source = url, usage = fetched.usage)
            }
            .onFailure { showBulletin(it.message ?: "Не удалось скачать подписку", BulletinKind.ERROR) }
    }

    /* ---------------- перенос между устройствами ---------------- */

    /** Телевизор или приставка: камеры нет, подписки приходят с телефона. */
    val isTv: Boolean = container.transfer.isTelevision

    /** Адрес приёма из отсканированного QR — ждёт подтверждения пользователя. */
    var pendingPairing by mutableStateOf<String?>(null)
        private set

    fun dismissTransfer() {
        pendingPairing = null
    }

    /** Открывает приём на этом устройстве; вызывающий обязан закрыть сессию. */
    fun startReceiving(): ReceiveSession? = container.transfer.startReceiving { receiveTransfer(it) }

    /** Пришло с другого устройства: подписки скачиваются, серверы добавляются. */
    fun receiveTransfer(payload: TransferPayload) {
        scope.launch {
            busy = true
            bulletin = null
            val before = servers.size
            payload.subscriptions
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .forEach { fetchAndAdd(it) }
            if (payload.servers.isNotEmpty()) {
                applyBody(
                    SubscriptionPayload.classify(payload.servers.joinToString("\n")),
                    source = null,
                    usage = null,
                )
            }
            busy = false
            showBulletin(
                "Получено: подписок ${payload.subscriptions.size}, новых серверов ${servers.size - before}",
                BulletinKind.SUCCESS,
            )
        }
    }

    /** Отправляет всё, что есть на этом устройстве, на приставку из QR. */
    fun confirmTransfer() {
        val address = pendingPairing ?: return
        pendingPairing = null
        val payload = TransferPayload(
            subscriptions = subscriptions.map { it.url },
            servers = servers.filter { it.source == null }.mapNotNull { ShareLink.encode(it) },
        )
        if (payload.isEmpty) {
            showBulletin("Нечего отправлять: добавьте подписку или сервер", BulletinKind.ERROR)
            return
        }
        scope.launch {
            busy = true
            container.transfer.send(address, payload)
                .onSuccess {
                    showBulletin(
                        "Отправлено: подписок ${payload.subscriptions.size}, серверов ${payload.servers.size}",
                        BulletinKind.SUCCESS,
                    )
                }
                .onFailure { showBulletin(it.message ?: "Не удалось отправить", BulletinKind.ERROR) }
            busy = false
        }
    }

    /* ---------------- ссылки и «Поделиться» ---------------- */

    fun linkFor(server: ProxyConfig): String? = ShareLink.encode(server)

    fun share(text: String) = container.transfer.shareText(text)

    fun refreshSubscription(subscription: Subscription) {
        scope.launch {
            busy = true
            bulletin = null
            container.subscriptions.fetch(subscription.url)
                .onSuccess { fetched ->
                    applyBody(
                        SubscriptionPayload.classify(fetched.body),
                        source = subscription.url,
                        replaceSource = true,
                        usage = fetched.usage,
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
                .onFailure { showBulletin(it.message ?: "Подписка недоступна", BulletinKind.ERROR) }
            busy = false
        }
    }

    fun refreshAll() {
        subscriptions.forEach { refreshSubscription(it) }
    }

    fun removeSubscription(subscription: Subscription) {
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
    ) {
        if (body is SubscriptionPayload.Body.Failure) {
            showBulletin(body.message, BulletinKind.ERROR)
            return
        }
        body as SubscriptionPayload.Body.Ok

        var base = if (replaceSource && source != null) {
            servers.filterNot { it.source == source }
        } else {
            servers
        }

        val incoming = body.nodes.mapTo(HashSet()) { it.dedupKey() }

        // Ноды, добавленные до появления подписок, привязываем к ней,
        // иначе они навсегда останутся в «Вручную».
        if (source != null) {
            base = base.map { server ->
                if (server.source == null && server.dedupKey() in incoming) {
                    server.copy(source = source)
                } else {
                    server
                }
            }
        }

        val known = base.mapTo(HashSet()) { it.dedupKey() }
        val fresh = body.nodes
            .filterNot { it.dedupKey() in known }
            .map { it.copy(source = source) }

        val added = fresh.size
        val next = base + fresh

        servers = next
        container.store.save(next)

        if (source != null) {
            val existing = subscriptions.firstOrNull { it.url == source }
            val entry = Subscription(
                url = source,
                // Имя подписки — её хост: панель заголовка не отдаёт,
                // а имя первой ноды вводит в заблуждение.
                title = source.substringAfter("://").substringBefore('/'),
                lastFetched = currentTimeMillis(),
                nodeCount = next.count { it.source == source },
                usedBytes = (usage?.used ?: existing?.usedBytes ?: 0L).takeIf { it > 0 } ?: 0L,
                totalBytes = usage?.total ?: existing?.totalBytes ?: 0L,
                expireAt = usage?.expireAt?.takeIf { it > 0 } ?: existing?.expireAt ?: 0L,
            )
            subscriptions = subscriptions.filterNot { it.url == source } + entry
            container.store.saveSubscriptions(subscriptions)
        }

        if (selectedId == null) {
            selectedId = next.firstOrNull()?.id
            container.store.saveSelectedId(selectedId)
        }

        showBulletin(when {
            added > 0 -> buildString {
                append(body.format.label).append(": +").append(added)
                if (body.skipped.isNotEmpty()) append(" · ").append(body.skipped.first())
            }
            source != null -> {
                val count = next.count { it.source == source }
                "Подписка обновлена · $count нод"
            }
            else -> "Новых серверов нет — эти уже добавлены"
        }, if (source != null) BulletinKind.SUCCESS else BulletinKind.INFO)
        busy = false
    }

    /* ---------------- подключение ---------------- */

    fun toggleTunnel(status: VpnStatus) {
        when (status.phase) {
            VpnPhase.CONNECTED -> scope.launch { container.vpn.disconnect() }
            VpnPhase.PAUSED -> scope.launch { container.vpn.resume() }
            VpnPhase.CONNECTING -> Unit
            VpnPhase.DISCONNECTED, VpnPhase.ERROR -> connect()
        }
    }

    private fun connect() {
        if (connectJob?.isActive == true) return
        val server = selected
        if (server == null) {
            showBulletin("Сначала добавьте сервер", BulletinKind.ERROR)
            return
        }
        if (!server.isUsable) {
            showBulletin("Протокол ${server.proto.wire} требует sing-box, а сборка на Xray", BulletinKind.ERROR)
            return
        }
        if (!coreAvailable) {
            showBulletin("xray.aar не подключён к сборке", BulletinKind.ERROR)
            return
        }
        if (settings.splitMode == SplitMode.INCLUDE && settings.splitPackages.isEmpty()) {
            showBulletin("Режим «Только»: выберите приложения в разделе «Раздельный туннель»", BulletinKind.ERROR)
            return
        }
        bulletin = null
        connectJob = scope.launch {
            val options = settings.toTunnelOptions()

            // Сначала настоящая проверка узла: TCP-пинг показывает только
            // открытый порт, а тут проверяется и рукопожатие, и авторизация.
            val picked = pickWorkingNode(server, options)

            if (picked == null) {
                // Проверка — эвристика: на мобильных сетях она может не пройти
                // из-за самого оператора. Поэтому не запрещаем подключение,
                // но предупреждаем, что узел не подтвердился.
                showBulletin("Узлы не ответили на проверку — пробуем «${server.name}»", BulletinKind.INFO)
            }

            val target = picked ?: server
            if (target.id != server.id) {
                selectedId = target.id
                container.store.saveSelectedId(target.id)
                showBulletin("«${server.name}» не отвечает — подключились к «${target.name}»", BulletinKind.INFO)
            }

            container.vpn.connect(target, options).onFailure {
                showBulletin(it.message ?: "Не удалось подключиться", BulletinKind.ERROR)
            }
        }
    }

    /**
     * Проверяет выбранный узел, а если он мёртв — перебирает быстрые
     * альтернативы из той же подписки (сначала с наименьшим пингом).
     */
    private suspend fun pickWorkingNode(
        server: ProxyConfig,
        options: dev.local.veil.core.TunnelOptions,
    ): ProxyConfig? {
        suspend fun check(candidate: ProxyConfig): Boolean {
            checking = true
            checkingServer = candidate
            return try {
                container.vpn.probe(candidate, options)
            } finally {
                checking = false
                checkingServer = null
            }
        }

        if (check(server)) return server

        val alternatives = servers
            .filter { it.id != server.id && it.isUsable && it.source == server.source }
            .sortedBy { pings[it.id] ?: Int.MAX_VALUE }
            .take(4)

        for (alternative in alternatives) {
            if (check(alternative)) return alternative
        }
        return null
    }

    /** Кнопка «Проверить соединение» в настройках. */
    fun checkConnection() {
        val server = selected
        if (server == null) {
            showBulletin("Сначала выберите сервер", BulletinKind.ERROR)
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
                    container.vpn.realPing(server, settings.toTunnelOptions(), timeoutMs = 6000)
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
                    val tcpPart = tcp?.let { " · TCP $it мс" }.orEmpty()
                    showBulletin("«${server.name}» отвечает · реальный $real мс$tcpPart", BulletinKind.SUCCESS)
                }
                tcp != null -> {
                    if (pingMode == PingMode.TCP) pings = pings + (server.id to tcp)
                    val note = if (container.vpn.coreAvailable) {
                        "порт открыт ($tcp мс), но через узел трафик не идёт"
                    } else {
                        "порт открыт · TCP $tcp мс"
                    }
                    showBulletin("«${server.name}»: $note", if (container.vpn.coreAvailable) BulletinKind.ERROR else BulletinKind.SUCCESS)
                }
                else -> showBulletin("«${server.name}» не отвечает", BulletinKind.ERROR)
            }
        }
    }

    /* ---------------- обновления ---------------- */

    fun checkUpdates() {
        scope.launch {
            updateProgress = null
            container.update.latest()
                .onSuccess { release ->
                    if (release == null) {
                        showBulletin("Обновлений нет · версия ${appVersion}", BulletinKind.SUCCESS)
                    } else {
                        update = release
                    }
                }
                .onFailure {
                    val text = it.message ?: "Не удалось проверить обновления"
                    // Отсутствие релизов — не ошибка, а факт.
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
                        .onSuccess { showBulletin("Открываю установщик Android", BulletinKind.INFO) }
                        .onFailure { showBulletin(it.message ?: "Не удалось запустить установку", BulletinKind.ERROR) }
                }
                .onFailure {
                    updateProgress = null
                    showBulletin(it.message ?: "Не удалось скачать обновление", BulletinKind.ERROR)
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

/** Тип всплывающего сообщения: от него зависит цвет и время показа. */
enum class BulletinKind { INFO, SUCCESS, ERROR }

data class Bulletin(val id: Long, val text: String, val kind: BulletinKind)

/** Локальное время без зависимостей: для отметки «когда обновляли». */
expect fun currentTimeMillis(): Long
