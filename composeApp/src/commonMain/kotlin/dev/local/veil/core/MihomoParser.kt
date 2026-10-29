package dev.local.veil.core

/**
 * Разбор Mihomo/Clash YAML.
 *
 * Панели, которые отдают Clash-профили, обычно присылают простой YAML: список
 * `proxies` с плоскими ключами и одним-двумя вложенными блоками (`ws-opts`,
 * `reality-opts`, `grpc-opts`). Полноценный YAML тут не нужен — разбираем ровно
 * этот подмножество, чтобы не тянуть парсер в общий код.
 *
 * Группы (`proxy-groups`) и правила (`rules`) пока не импортируются: сначала
 * узлы, чтобы подписка хотя бы работала.
 */
object MihomoParser {

    fun parse(text: String): SubscriptionPayload.Body? {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trimEnd() == "proxies:" || it.trim() == "proxies:" }
        if (start < 0) return null

        val proxiesIndent = indentOf(lines[start])
        val items = ArrayList<Map<String, String>>()
        var itemIndent = -1
        var current: MutableMap<String, String>? = null
        val path = ArrayList<Pair<Int, String>>()

        var index = start + 1
        while (index < lines.size) {
            val raw = lines[index]
            index++
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            val indent = indentOf(raw)
            if (indent <= proxiesIndent) break // секция кончилась

            if (trimmed.startsWith("- ")) {
                if (itemIndent < 0) itemIndent = indent
                if (indent != itemIndent) continue

                current?.let { items += it }
                current = LinkedHashMap()
                path.clear()
                // Первая пара ключ-значение идёт сразу после дефиса.
                readPair(trimmed.removePrefix("- ").trim(), itemIndent + 2, current, path)
                continue
            }

            val target = current ?: continue
            readPair(trimmed, indent, target, path)
        }
        current?.let { items += it }

        if (items.isEmpty()) return null

        val nodes = ArrayList<ProxyConfig>()
        val skipped = ArrayList<String>()

        for (raw in items) {
            val name = raw["name"].orEmpty().ifBlank { raw["server"].orEmpty() }
            val node = toProxyConfig(raw)
            if (node == null) {
                skipped += name.ifBlank { "proxy" }
            } else {
                nodes += node
            }
        }

        if (nodes.isEmpty()) {
            return SubscriptionPayload.Body.Failure(
                SubscriptionPayload.Format.MIHOMO,
                "В Mihomo-профиле нет узлов, которые умеет Xray",
                skipped = skipped,
            )
        }
        return SubscriptionPayload.Body.Ok(SubscriptionPayload.Format.MIHOMO, nodes, skipped)
    }

    private fun indentOf(line: String): Int = line.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }

    /**
     * Складывает пару в плоскую карту с путём: `ws-opts.headers.Host`.
     * Пустое значение означает начало вложенного блока.
     */
    private fun readPair(
        text: String,
        indent: Int,
        target: MutableMap<String, String>,
        path: MutableList<Pair<Int, String>>,
    ) {
        val colon = text.indexOf(':')
        if (colon <= 0) return
        val key = text.substring(0, colon).trim()
        val value = unquote(text.substring(colon + 1).trim())

        while (path.isNotEmpty() && path.last().first >= indent) path.removeAt(path.lastIndex)

        val fullKey = (path.map { it.second } + key).joinToString(".")
        if (value.isEmpty()) {
            path += indent to key
        } else {
            target[fullKey] = value
        }
    }

    private fun unquote(value: String): String {
        val withoutComment = value.substringBefore(" #").trim()
        return when {
            withoutComment.length >= 2 && withoutComment.startsWith("\"") && withoutComment.endsWith("\"") ->
                withoutComment.substring(1, withoutComment.length - 1)
            withoutComment.length >= 2 && withoutComment.startsWith("'") && withoutComment.endsWith("'") ->
                withoutComment.substring(1, withoutComment.length - 1)
            else -> withoutComment
        }
    }

    private fun toProxyConfig(raw: Map<String, String>): ProxyConfig? {
        val type = raw["type"]?.lowercase() ?: return null
        val proto = when (type) {
            "vless" -> ProxyProtocol.VLESS
            "vmess" -> ProxyProtocol.VMESS
            "trojan" -> ProxyProtocol.TROJAN
            "ss", "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
            else -> return null // hysteria2, tuic, anytls, ssh и прочее — не для Xray
        }

        val server = raw["server"] ?: return null
        val port = raw["port"]?.toIntOrNull() ?: return null

        val network = when (raw["network"]?.lowercase()) {
            "ws" -> TransportNetwork.WS
            "grpc" -> TransportNetwork.GRPC
            "http", "h2" -> TransportNetwork.HTTP
            "xhttp" -> TransportNetwork.XHTTP
            else -> TransportNetwork.TCP
        }

        val reality = raw.keys.any { it.startsWith("reality-opts.") }
        val tls = raw["tls"]?.equals("true", ignoreCase = true) == true || reality
        val security = when {
            reality -> StreamSecurity.REALITY
            tls -> StreamSecurity.TLS
            else -> StreamSecurity.NONE
        }

        val sni = raw["servername"] ?: raw["sni"] ?: raw["ws-opts.headers.Host"]
        val path = raw["ws-opts.path"] ?: raw["http-opts.path"]
        val host = raw["ws-opts.headers.Host"]

        return ProxyConfig(
            name = raw["name"].orEmpty().ifBlank { "$server:$port" },
            proto = proto,
            address = server,
            port = port,
            uuid = raw["uuid"] ?: raw["password"].takeIf { proto == ProxyProtocol.VMESS },
            password = when (proto) {
                ProxyProtocol.TROJAN, ProxyProtocol.SHADOWSOCKS -> raw["password"]
                else -> null
            },
            method = raw["cipher"],
            alterId = raw["alterId"]?.toIntOrNull() ?: raw["alter-id"]?.toIntOrNull(),
            flow = raw["flow"],
            encryption = raw["encryption"],
            network = network,
            security = security,
            sni = sni,
            alpn = raw["alpn"]?.let { parseList(it) },
            fingerprint = raw["client-fingerprint"],
            allowInsecure = raw["skip-cert-verify"]?.equals("true", ignoreCase = true) == true,
            publicKey = raw["reality-opts.public-key"],
            shortId = raw["reality-opts.short-id"],
            path = path,
            host = host,
            serviceName = raw["grpc-opts.grpc-service-name"],
            xhttpMode = raw["xhttp-opts.mode"],
        )
    }

    private fun parseList(value: String): List<String> =
        value.trim().removePrefix("[").removeSuffix("]")
            .split(',')
            .map { unquote(it.trim()) }
            .filter { it.isNotEmpty() }
}
