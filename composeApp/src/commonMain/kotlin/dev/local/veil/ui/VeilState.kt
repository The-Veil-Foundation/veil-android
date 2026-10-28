package dev.local.veil.ui

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
    var settings by mutableStateOf(container.settings.load())
        private set
    var servers by mutableStateOf(container.store.load())
        private set
    var subscriptions by mutableStateOf(container.store.loadSubscriptions())
        private set
    var selectedId by mutableStateOf(container.store.loadSelectedId() ?: container.store.load().firstOrNull()?.id)
        private set
    var pings by mutableStateOf<Map<String, Int?>>(emptyMap())
        private set
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
                val target = pickReachableNode(server)
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

    fun pingAll() {
        if (pinging || servers.isEmpty()) return
        pinging = true
        pings = emptyMap()

        scope.launch {
            val semaphore = Semaphore(8)
            val results = servers.map { server ->
                async(Dispatchers.IO) {
                    val value = semaphore.withPermit {
                        container.pinger.ping(server, timeoutMs = 1500)
                    }
                    value
                }
            }.awaitAll()

            pings = servers.mapIndexed { index, server -> server.id to results[index] }.toMap()
            pinging = false
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
            container.subscriptions.fetch(url)
                .onSuccess { fetched ->
                    applyBody(SubscriptionPayload.classify(fetched.body), source = url, usage = fetched.usage)
                }
                .onFailure { showBulletin(it.message ?: "Не удалось скачать подписку", BulletinKind.ERROR) }
            busy = false
        }
    }

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
     * Быстрая проверка при переключении уже поднятого туннеля.
     *
     * Полная проба здесь не годится: она останавливает ядро, и Телеграм
     * сбрасывает соединения. Поэтому проверяем только TCP-доступность узла
     * (сокет идёт мимо туннеля, приложение исключено из VPN) — это 0,1–2 с,
     * туннель всё это время продолжает работать.
     */
    private suspend fun pickReachableNode(server: ProxyConfig): ProxyConfig? {
        suspend fun reachable(candidate: ProxyConfig): Int? = withContext(Dispatchers.IO) {
            container.pinger.ping(candidate, timeoutMs = 2000)
        }

        checking = true
        checkingServer = server
        val first = try {
            reachable(server)
        } finally {
            checking = false
            checkingServer = null
        }

        if (first != null) {
            pings = pings + (server.id to first)
            return server
        }
        pings = pings + (server.id to null)

        val alternatives = servers
            .filter { it.id != server.id && it.isUsable && it.source == server.source }
            .sortedBy { pings[it.id] ?: Int.MAX_VALUE }
            .take(5)

        for (alternative in alternatives) {
            checking = true
            checkingServer = alternative
            val value = try {
                reachable(alternative)
            } finally {
                checking = false
                checkingServer = null
            }
            pings = pings + (alternative.id to value)
            if (value != null) return alternative
        }
        return null
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
            val rtt = try {
                withContext(Dispatchers.IO) { container.pinger.ping(server, timeoutMs = 4000) }
            } finally {
                checking = false
                checkingServer = null
            }
            if (rtt == null) {
                showBulletin("«${server.name}» не отвечает", BulletinKind.ERROR)
            } else {
                pings = pings + (server.id to rtt)
                showBulletin("«${server.name}» отвечает · $rtt мс", BulletinKind.SUCCESS)
            }
        }
    }

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
