package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Куда ядро смотрит за трафиком. */
sealed interface XrayInbound {
    data class Tun(val name: String = "veil0", val mtu: Int = 1500) : XrayInbound
    /** Если [user] задан, SOCKS и HTTP требуют логин и пароль: другие приложения на телефоне порт не используют. */
    data class LocalProxy(
        val socks: Int = 10808,
        val http: Int = 10809,
        val user: String? = null,
        val pass: String? = null,
    ) : XrayInbound
}

/** Настройки, которые влияют на конфиг туннеля. */
data class TunnelOptions(
    val mtu: Int = 1500,
    val ipv6: Boolean = true,
    val logLevel: String = "warning",
    val logFile: String? = null,
    val dnsServers: List<String> = listOf("1.1.1.1", "1.0.0.1"),
    val stats: Boolean = true,
    val inboundName: String = "veil0",
    val fragment: Boolean = false,
    /** Какое ядро запускать: AUTO выбирает по протоколу узла. */
    val coreEngine: CoreEngine = CoreEngine.AUTO,
    /** Упорядоченные правила: см. [AppSettings.effectiveRoutingRules]. */
    val rules: List<RoutingRule> = emptyList(),
    /** Полные настройки DNS (null — использовать legacy [dnsServers]). */
    val dnsSettings: DnsSettings? = null,
    /** Участники группы «самый быстрый»: строим leastPing-балансировщик. */
    val balancerMembers: List<ProxyConfig> = emptyList(),
    /**
     * Есть ли в каталоге ядра geoip.dat и geosite.dat. Без них правило с
     * `geoip:`/`geosite:` не просто не сработает — ядро откажется стартовать
     * («failed to open geoip.dat»), поэтому такие матчеры вырезаются.
     */
    val geoAvailable: Boolean = false,
    /** Все узлы: из них строятся выходы для правил «через этот узел». */
    val servers: List<ProxyConfig> = emptyList(),
    /** Группы, на которые могут ссылаться правила. */
    val groups: List<ServerGroup> = emptyList(),
    /** Rule-set, добавленные руками (sing-box). */
    val ruleSets: List<RuleSetRef> = emptyList(),
    /** Секрет Clash API sing-box; пусто — API не поднимается. */
    val clashSecret: String = "",
) {
    /**
     * Опции для пробного ядра (пинг, проверка узла). Пробе достаточно дойти
     * до сервера: правила ей не нужны, а rule-set заставили бы её качать
     * наборы перед замером, и Clash API занял бы порт рабочего туннеля.
     */
    fun forProbe(): TunnelOptions = copy(
        rules = emptyList(),
        ruleSets = emptyList(),
        servers = emptyList(),
        groups = emptyList(),
        dnsSettings = dnsSettings?.copy(rules = emptyList()),
        clashSecret = "",
    )
}

/**
 * Собирает Xray-JSON из [ProxyConfig].
 *
 * Порт `XrayConfigBuilder.swift`. В отличие от macOS, правило, которое
 * называет узел или группу, здесь получает собственный выход (или
 * балансировщик), а не сводится к единственному `proxy`.
 */
object XrayConfig {

    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"
    const val TAG_DNS_OUT = "dns-out"
    const val TAG_TUN_IN = "tun-in"

    fun build(
        cfg: ProxyConfig,
        options: TunnelOptions = TunnelOptions(),
        inbound: XrayInbound = XrayInbound.Tun(options.inboundName, options.mtu),
    ): JsonObject {
        // Подписка может нести готовый конфиг: сохраняем её маршруты и DNS,
        // подменяя только inbounds — на Android пакеты приходят из нашего TUN.
        cfg.rawConfigJson?.let { raw ->
            val doc = runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrNull()
            if (doc != null) return patchRaw(doc, options, inbound)
        }

        val dnsSettings = options.dnsSettings?.takeIf { it.enabled }
        val hasDns = dnsSettings?.servers?.isNotEmpty() ?: options.dnsServers.isNotEmpty()
        val balancer = options.balancerMembers.filter { it.xrayCompatible }
        val useBalancer = balancer.size > 1
        val graph = OutboundGraph.build(
            rules = options.rules,
            servers = options.servers,
            groups = options.groups,
            capable = { it.xrayCompatible },
        )
        // «Самый быстрый» — балансировщик Xray; ручной выбор — его выбранный участник.
        val urltestGroups = graph.groups.filter { (group, members) ->
            group.kind == GroupKind.URLTEST && members.size > 1
        }
        val balancerTags = urltestGroups.map { ProfileTags.group(it.first.id) }.toSet()
        fun xrayTarget(target: RuleTarget): String {
            if (!target.needsProfile) return target.xrayTag()
            if (target.kind == TargetKind.GROUP) {
                val built = graph.groups.firstOrNull { it.first.id == target.id } ?: return TAG_PROXY
                val (group, members) = built
                if (ProfileTags.group(group.id) in balancerTags) return ProfileTags.group(group.id)
                val chosen = members.firstOrNull { it.id == group.selectedId } ?: members.first()
                return ProfileTags.server(chosen.id)
            }
            return graph.tagFor(target)
        }
        val routingRules = options.rules
            .mapNotNull { rule ->
                val tag = xrayTarget(rule.target)
                rule.xrayRule(outboundTag = tag, balancer = tag in balancerTags)
            }
            .let { rules -> if (options.geoAvailable) rules else rules.mapNotNull(::withoutGeoRule) }
        val observed = buildList {
            if (useBalancer) balancer.indices.forEach { add("bal-$it") }
            urltestGroups.forEach { (_, members) -> members.forEach { add(ProfileTags.server(it.id)) } }
        }.distinct()

        return buildJsonObject {
            put("log", logJson(options))
            put("inbounds", JsonArray(inboundList(inbound)))

            putJsonArray("outbounds") {
                if (useBalancer) {
                    balancer.forEachIndexed { index, member ->
                        add(outbound(member, "bal-$index", options))
                    }
                } else {
                    add(outbound(cfg, TAG_PROXY, options))
                }
                graph.servers.forEach { server -> add(outbound(server, ProfileTags.server(server.id), options)) }
                addJsonObject { put("tag", TAG_DIRECT); put("protocol", "freedom"); put("settings", JsonObject(emptyMap())) }
                addJsonObject { put("tag", TAG_BLOCK); put("protocol", "blackhole"); put("settings", JsonObject(emptyMap())) }
                if (hasDns) {
                    addJsonObject { put("tag", TAG_DNS_OUT); put("protocol", "dns"); put("settings", JsonObject(emptyMap())) }
                }
            }

            putJsonObject("routing") {
                put("domainStrategy", "IPIfNonMatch")
                putJsonArray("rules") {
                    if (hasDns) {
                        addJsonObject {
                            put("type", "field")
                            put("port", 53)
                            put("outboundTag", TAG_DNS_OUT)
                        }
                    }
                    // Первое совпавшее правило побеждает; порядок задаёт
                    // AppSettings.effectiveRoutingRules (охрана LAN — внутри).
                    routingRules.forEach { add(it) }
                    addJsonObject {
                        put("type", "field")
                        put("network", "tcp,udp")
                        if (useBalancer) put("balancerTag", TAG_PROXY) else put("outboundTag", TAG_PROXY)
                    }
                }
                if (useBalancer || urltestGroups.isNotEmpty()) {
                    putJsonArray("balancers") {
                        if (useBalancer) {
                            addJsonObject {
                                put("tag", TAG_PROXY)
                                putJsonArray("selector") { balancer.indices.forEach { add("bal-$it") } }
                                putJsonObject("strategy") { put("type", "leastPing") }
                            }
                        }
                        urltestGroups.forEach { (group, members) ->
                            addJsonObject {
                                put("tag", ProfileTags.group(group.id))
                                putJsonArray("selector") { members.forEach { add(ProfileTags.server(it.id)) } }
                                putJsonObject("strategy") { put("type", "leastPing") }
                            }
                        }
                    }
                }
            }

            if (observed.isNotEmpty()) {
                putJsonObject("observatory") {
                    putJsonArray("subjectSelector") { observed.forEach { add(it) } }
                    put("probeUrl", "https://www.gstatic.com/generate_204")
                    put("probeInterval", "1m")
                    put("enableConcurrency", true)
                }
            }

            if (hasDns) {
                putJsonObject("dns") {
                    if (dnsSettings != null) {
                        val clean = dnsSettings.sanitized()
                        putJsonArray("servers") {
                            clean.servers.forEach { entry ->
                                val host = if (entry.port != null) "${entry.server}:${entry.port}" else entry.server
                                add(entry.kind.address(host))
                            }
                        }
                    } else {
                        putJsonArray("servers") { options.dnsServers.forEach { add(it) } }
                    }
                    put("queryStrategy", if (options.ipv6) "UseIP" else "UseIPv4")
                }
            }

            if (options.stats) {
                put("stats", JsonObject(emptyMap()))
                put("policy", policyJson())
            }
        }
    }

    /** Конфиг из подписки: всё как есть, кроме log/inbounds/stats. */
    private fun patchRaw(
        doc: JsonObject,
        options: TunnelOptions,
        inbound: XrayInbound,
    ): JsonObject {
        val mutable = doc.toMutableMap()
        mutable["log"] = logJson(options)
        mutable["inbounds"] = JsonArray(inboundList(inbound))
        if (!options.geoAvailable) {
            (mutable["routing"] as? JsonObject)?.let { mutable["routing"] = withoutGeoRouting(it) }
            (mutable["dns"] as? JsonObject)?.let { mutable["dns"] = withoutGeoDns(it) }
        }
        if (!mutable.containsKey("stats")) mutable["stats"] = JsonObject(emptyMap())
        if (!mutable.containsKey("policy")) mutable["policy"] = policyJson()
        return JsonObject(mutable)
    }

    /* ---------------- geo-матчеры без базы ---------------- */

    /** То, что значит `geoip:private`, перечисленное явно. */
    private val PRIVATE_NETWORKS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16",
        "127.0.0.0/8", "fc00::/7", "fe80::/10", "::1/128",
    )

    /** Домены из `geosite:private`: имена, которые существуют только в локальной сети. */
    private val PRIVATE_DOMAINS = listOf(
        "full:localhost", "domain:local", "domain:localdomain", "domain:lan", "domain:home.arpa",
    )

    /** Поля правила, где допустимы geo-матчеры. */
    private val GEO_RULE_FIELDS = listOf("domain", "domains", "ip", "source", "sourceIP", "localIP")

    private fun isGeoToken(token: String) =
        token.startsWith("geoip:") || token.startsWith("geosite:") ||
            token.startsWith("ext:") || token.startsWith("ext-ip:") || token.startsWith("ext-domain:")

    /**
     * Список матчеров без geo-токенов. `private` заменяется явными значениями,
     * остальные geo-токены убираются. null — в списке остались только они:
     * без них правило превратилось бы в «всё подряд».
     */
    private fun withoutGeoTokens(list: JsonArray): JsonArray? {
        var touched = false
        val out = ArrayList<JsonElement>()
        for (item in list) {
            val token = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
            when {
                token == null -> out.add(item)
                token == "geoip:private" -> {
                    touched = true
                    PRIVATE_NETWORKS.forEach { out.add(JsonPrimitive(it)) }
                }
                token == "geosite:private" -> {
                    touched = true
                    PRIVATE_DOMAINS.forEach { out.add(JsonPrimitive(it)) }
                }
                isGeoToken(token) -> touched = true
                else -> out.add(item)
            }
        }
        return if (out.isEmpty() && touched) null else JsonArray(out)
    }

    /** Правило без geo-матчеров; null — убрать его целиком, чтобы оно не расширилось. */
    private fun withoutGeoRule(rule: JsonObject): JsonObject? {
        val out = rule.toMutableMap()
        for (field in GEO_RULE_FIELDS) {
            val list = rule[field] as? JsonArray ?: continue
            out[field] = withoutGeoTokens(list) ?: return null
        }
        return JsonObject(out)
    }

    private fun withoutGeoRouting(routing: JsonObject): JsonObject {
        val rules = routing["rules"] as? JsonArray ?: return routing
        val kept = rules.mapNotNull { rule -> if (rule is JsonObject) withoutGeoRule(rule) else rule }
        return JsonObject(routing.toMutableMap().apply { put("rules", JsonArray(kept)) })
    }

    private fun withoutGeoDns(dns: JsonObject): JsonObject {
        val out = dns.toMutableMap()
        (dns["servers"] as? JsonArray)?.let { servers ->
            out["servers"] = JsonArray(
                servers.mapNotNull { server ->
                    if (server !is JsonObject) return@mapNotNull server
                    val copy = server.toMutableMap()
                    for (field in listOf("domains", "expectIPs", "expectedIPs", "unexpectedIPs")) {
                        val list = server[field] as? JsonArray ?: continue
                        // Резолвер, который отвечал только за geo-список, убираем целиком.
                        copy[field] = withoutGeoTokens(list) ?: return@mapNotNull null
                    }
                    JsonObject(copy)
                },
            )
        }
        (dns["hosts"] as? JsonObject)?.let { hosts ->
            out["hosts"] = JsonObject(hosts.filterKeys { !isGeoToken(it) })
        }
        return JsonObject(out)
    }

    private fun logJson(options: TunnelOptions): JsonObject = buildJsonObject {
        put("loglevel", options.logLevel)
        put("access", if (options.logLevel == "debug") "" else "none")
        options.logFile?.takeIf { it.isNotEmpty() }?.let {
            put("error", it)
            put("access", "$it.access")
        }
    }

    private fun policyJson(): JsonObject = buildJsonObject {
        putJsonObject("system") {
            put("statsOutboundUplink", true)
            put("statsOutboundDownlink", true)
        }
    }

    private fun inboundList(inbound: XrayInbound): List<JsonObject> = when (inbound) {
        is XrayInbound.Tun -> listOf(
            buildJsonObject {
                put("tag", TAG_TUN_IN)
                put("port", 0)
                put("protocol", "tun")
                putJsonObject("settings") {
                    put("name", inbound.name)
                    put("MTU", inbound.mtu)
                }
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                    put("routeOnly", false)
                }
            },
        )

        is XrayInbound.LocalProxy -> listOf(
            buildJsonObject {
                put("tag", "socks-in")
                put("listen", "127.0.0.1")
                put("port", inbound.socks)
                put("protocol", "socks")
                putJsonObject("settings") {
                    put("udp", true)
                    if (inbound.user != null && inbound.pass != null) {
                        put("auth", "password")
                        putJsonArray("accounts") {
                            add(buildJsonObject { put("user", inbound.user); put("pass", inbound.pass) })
                        }
                    } else {
                        put("auth", "noauth")
                    }
                }
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls"); add("quic") }
                }
            },
            buildJsonObject {
                put("tag", "http-in")
                put("listen", "127.0.0.1")
                put("port", inbound.http)
                put("protocol", "http")
                if (inbound.user != null && inbound.pass != null) {
                    putJsonObject("settings") {
                        putJsonArray("accounts") {
                            add(buildJsonObject { put("user", inbound.user); put("pass", inbound.pass) })
                        }
                    }
                }
                putJsonObject("sniffing") {
                    put("enabled", true)
                    putJsonArray("destOverride") { add("http"); add("tls") }
                }
            },
        )
    }

    fun json(
        cfg: ProxyConfig,
        options: TunnelOptions = TunnelOptions(),
        inbound: XrayInbound = XrayInbound.Tun(options.inboundName, options.mtu),
    ): String = Json.encodeToString(JsonObject.serializer(), build(cfg, options, inbound))

    /* ---------------- outbounds ---------------- */

    fun outbound(cfg: ProxyConfig, tag: String, options: TunnelOptions = TunnelOptions()): JsonObject {
        fun JsonObject.withTag(): JsonObject = JsonObject(toMutableMap().apply { put("tag", kotlinx.serialization.json.JsonPrimitive(tag)) })

        val out: JsonObject = when (cfg.proto) {
            ProxyProtocol.VLESS -> vless(cfg, options)
            ProxyProtocol.VMESS -> vmess(cfg, options)
            ProxyProtocol.TROJAN -> trojan(cfg, options)
            ProxyProtocol.SHADOWSOCKS -> shadowsocks(cfg, options)
            ProxyProtocol.WIREGUARD -> wireguard(cfg)
            ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC, ProxyProtocol.ANYTLS ->
                buildJsonObject {
                    put("protocol", "freedom")
                    put("settings", JsonObject(emptyMap()))
                }
        }
        return out.withTag()
    }

    private fun vless(cfg: ProxyConfig, options: TunnelOptions): JsonObject = buildJsonObject {
        put("protocol", "vless")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", cfg.uuid ?: "")
                            put("encryption", cfg.encryption ?: "none")
                            cfg.flow?.takeIf { it.isNotEmpty() }?.let { put("flow", it) }
                        }
                    }
                }
            }
        }
        put("streamSettings", streamSettings(cfg, options))
    }

    private fun vmess(cfg: ProxyConfig, options: TunnelOptions): JsonObject = buildJsonObject {
        put("protocol", "vmess")
        putJsonObject("settings") {
            putJsonArray("vnext") {
                addJsonObject {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    putJsonArray("users") {
                        addJsonObject {
                            put("id", cfg.uuid ?: "")
                            put("alterId", cfg.alterId ?: 0)
                            put("security", "auto")
                        }
                    }
                }
            }
        }
        put("streamSettings", streamSettings(cfg, options))
    }

    private fun trojan(cfg: ProxyConfig, options: TunnelOptions): JsonObject = buildJsonObject {
        put("protocol", "trojan")
        putJsonObject("settings") {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("password", cfg.password ?: "")
                }
            }
        }
        put("streamSettings", streamSettings(cfg, options))
    }

    private fun shadowsocks(cfg: ProxyConfig, options: TunnelOptions): JsonObject = buildJsonObject {
        put("protocol", "shadowsocks")
        putJsonObject("settings") {
            putJsonArray("servers") {
                addJsonObject {
                    put("address", cfg.address)
                    put("port", cfg.port)
                    put("method", cfg.method ?: "aes-256-gcm")
                    put("password", cfg.password ?: "")
                }
            }
        }
        put("streamSettings", streamSettings(cfg, options))
    }

    private fun wireguard(cfg: ProxyConfig): JsonObject = buildJsonObject {
        put("protocol", "wireguard")
        putJsonObject("settings") {
            put("secretKey", cfg.privateKey ?: "")
            putJsonArray("address") {
                (cfg.localAddresses ?: listOf("10.0.0.2/32")).forEach { add(it) }
            }
            putJsonArray("peers") {
                addJsonObject {
                    val endpoint = if (cfg.address.contains(":")) "[${cfg.address}]:${cfg.port}"
                    else "${cfg.address}:${cfg.port}"
                    put("endpoint", endpoint)
                    put("publicKey", cfg.peerPublicKey ?: "")
                    putJsonArray("allowedIPs") { cfg.effectiveAllowedIPs.forEach { add(it) } }
                    put("keepAlive", 25)
                    cfg.presharedKey?.takeIf { it.isNotEmpty() }?.let { put("preSharedKey", it) }
                }
            }
            cfg.mtu?.takeIf { it > 0 }?.let { put("mtu", it) }
            cfg.reserved?.takeIf { it.isNotEmpty() }?.let { r ->
                putJsonArray("reserved") { r.forEach { add(it) } }
            }
        }
    }

    /* ---------------- transport + security ---------------- */

    private fun streamSettings(cfg: ProxyConfig, options: TunnelOptions = TunnelOptions()): JsonObject = buildJsonObject {
        put("network", cfg.network.wire)

        if (options.fragment) {
            // Xray 26: маски живут в streamSettings.finalmask, а не в заголовке TCP.
            // Режем первые пакеты (ClientHello) — DPI не видит цельный SNI.
            putJsonObject("finalmask") {
                putJsonArray("tcp") {
                    addJsonObject {
                        put("type", "fragment")
                        putJsonObject("settings") {
                            put("packets", "tlshello")
                            put("length", "100-200")
                            put("delay", "10-20")
                        }
                    }
                }
            }
        }
        putJsonObject("sockopt") {
            put("tcpNoDelay", true)
            put("tcpKeepAliveIdle", 30)
            put("tcpKeepAliveInterval", 15)
        }

        when (cfg.security) {
            StreamSecurity.TLS -> {
                put("security", "tls")
                put("tlsSettings", tlsSettings(cfg))
            }
            StreamSecurity.REALITY -> {
                put("security", "reality")
                put("realitySettings", realitySettings(cfg))
            }
            StreamSecurity.NONE -> put("security", "none")
        }

        when (cfg.network) {
            TransportNetwork.WS -> putJsonObject("wsSettings") {
                put("path", cfg.path ?: "/")
                cfg.host?.takeIf { it.isNotEmpty() }?.let {
                    putJsonObject("headers") { put("Host", it) }
                }
            }
            TransportNetwork.GRPC -> putJsonObject("grpcSettings") {
                put("serviceName", cfg.serviceName ?: cfg.path ?: "")
            }
            TransportNetwork.HTTP -> putJsonObject("httpSettings") {
                put("path", cfg.path ?: "/")
                cfg.host?.takeIf { it.isNotEmpty() }?.let { h ->
                    putJsonArray("host") { add(h) }
                }
            }
            TransportNetwork.XHTTP -> putJsonObject("xhttpSettings") {
                put("path", cfg.path ?: "/")
                cfg.host?.takeIf { it.isNotEmpty() }?.let { put("host", it) }
                cfg.xhttpMode?.takeIf { it.isNotEmpty() }?.let { put("mode", it) }

                val extra = LinkedHashMap<String, JsonElement>()
                cfg.xPaddingBytes?.takeIf { it.isNotEmpty() }?.let {
                    extra["xPaddingBytes"] = kotlinx.serialization.json.JsonPrimitive(it)
                }
                cfg.xhttpExtra?.let { raw ->
                    runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrNull()?.let { parsed ->
                        parsed.forEach { (k, v) -> extra[k] = v }
                    }
                }
                if (extra.isNotEmpty()) put("extra", JsonObject(extra))
            }
            TransportNetwork.TCP -> Unit
            TransportNetwork.KCP, TransportNetwork.QUIC -> Unit
        }
    }

    private fun tlsSettings(cfg: ProxyConfig): JsonObject = buildJsonObject {
        put("allowInsecure", cfg.allowInsecure)
        cfg.sni?.takeIf { it.isNotEmpty() }?.let { put("serverName", it) }
        cfg.alpn?.takeIf { it.isNotEmpty() }?.let { list ->
            putJsonArray("alpn") { list.forEach { add(it) } }
        }
        cfg.fingerprint?.takeIf { it.isNotEmpty() }?.let { put("fingerprint", it) }
    }

    private fun realitySettings(cfg: ProxyConfig): JsonObject = buildJsonObject {
        cfg.sni?.let { put("serverName", it) }
        cfg.fingerprint?.let { put("fingerprint", it) }
        cfg.publicKey?.let { put("publicKey", it) }
        cfg.shortId?.let { put("shortId", it) }
        cfg.spiderX?.let { put("spiderX", it) }
    }
}
