package dev.local.veil.core

import kotlinx.serialization.json.*

/**
 * Генератор конфига для sing-box ядра.
 * 
 * Формат: https://sing-box.sagernet.org/configuration/
 * sing-box использует упрощённую структуру по сравнению с Xray.
 */
object SingboxConfig {

    const val TAG_PROXY = "proxy"
    const val TAG_DIRECT = "direct"
    const val TAG_BLOCK = "block"

    /** Куда sing-box смотрит за трафиком: рабочий TUN или локальный SOCKS для проверки. */
    sealed interface Inbound {
        data class Tun(val name: String = "veil0", val mtu: Int = 1500) : Inbound
        data class LocalProxy(
            val socks: Int = 10808,
            val user: String? = null,
            val pass: String? = null,
        ) : Inbound
    }

    fun build(
        cfg: ProxyConfig,
        options: TunnelOptions = TunnelOptions(),
        inbound: Inbound = Inbound.Tun(options.inboundName, options.mtu),
    ): JsonObject {
        val dnsSettings = options.dnsSettings?.takeIf { it.enabled }?.sanitized()
        val balancer = options.balancerMembers
        val useBalancer = balancer.size > 1
        val graph = OutboundGraph.build(
            rules = options.rules,
            servers = options.servers,
            groups = options.groups,
            capable = { it.singboxCompatible },
        )
        val rules = options.rules.filter { it.enabled }
        val tun = inbound is Inbound.Tun
        // `clash_mode` читает режим у Clash API, поэтому API включается сам,
        // как только он нужен хоть одному правилу. Только в туннеле и только
        // с секретом: иначе любое приложение на телефоне видит соединения.
        val clashApi = tun && options.clashSecret.isNotBlank() && rules.any { it.clashMode.isNotBlank() }

        return buildJsonObject {
            put("log", logJson(options))

            putJsonObject("dns") {
                if (dnsSettings != null) {
                    putJsonArray("servers") {
                        dnsSettings.servers.forEach { entry ->
                            addJsonObject {
                                put("type", entry.kind.name.lowercase())
                                put("tag", entry.tag)
                                if (entry.kind.needsServer) {
                                    put("server", entry.server)
                                    entry.port?.let { put("server_port", it) }
                                    if (entry.kind.needsPath && entry.path.isNotBlank()) put("path", entry.path)
                                }
                                if (entry.detour.isNotBlank() && entry.detour != TAG_DIRECT) {
                                    put("detour", graph.detourTag(entry.detour))
                                }
                                if (entry.strategy.isNotBlank()) put("strategy", entry.strategy)
                            }
                        }
                    }
                    putJsonArray("rules") {
                        dnsSettings.rules.filter { it.enabled && it.hasMatcher }.forEach { rule ->
                            dnsRule(rule)?.let { add(it) }
                        }
                    }
                    put("final", dnsSettings.finalTag)
                    put("strategy", dnsSettings.strategy)
                    put("reverse_mapping", dnsSettings.reverseMapping)
                } else {
                    putJsonArray("servers") {
                        options.dnsServers.forEach { dns ->
                            addJsonObject {
                                put("type", "udp")
                                put("tag", "dns-$dns")
                                put("server", dns)
                            }
                        }
                    }
                    put("strategy", if (options.ipv6) "prefer_ipv4" else "ipv4_only")
                }
            }

            putJsonArray("inbounds") {
                when (inbound) {
                    is Inbound.Tun -> addJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", inbound.name)
                        putJsonArray("address") {
                            add("${TunAddress.IPV4}/${TunAddress.IPV4_PREFIX}")
                            if (options.ipv6) add("${TunAddress.IPV6}/${TunAddress.IPV6_PREFIX}")
                        }
                        put("mtu", inbound.mtu)
                        // TUN создаёт VpnService (маршруты, DNS и split-tunnel
                        // уже настроены), ядру остаётся только fd.
                        put("auto_route", false)
                        put("strict_route", false)
                        put("stack", "gvisor")
                    }
                    is Inbound.LocalProxy -> addJsonObject {
                        put("type", "socks")
                        put("tag", "socks-in")
                        put("listen", "127.0.0.1")
                        put("listen_port", inbound.socks)
                        if (inbound.user != null && inbound.pass != null) {
                            putJsonArray("users") {
                                addJsonObject {
                                    put("username", inbound.user)
                                    put("password", inbound.pass)
                                }
                            }
                        }
                    }
                }
            }

            putJsonArray("outbounds") {
                if (useBalancer) {
                    balancer.forEachIndexed { index, member -> add(outbound(member, "bal-$index", options.fragment)) }
                    addJsonObject {
                        put("type", "urltest")
                        put("tag", TAG_PROXY)
                        putJsonArray("outbounds") { balancer.indices.forEach { add("bal-$it") } }
                        put("url", "https://www.gstatic.com/generate_204")
                        put("interval", probeIntervalFor(balancer.size))
                        put("tolerance", 50)
                    }
                } else {
                    add(outbound(cfg, TAG_PROXY, options.fragment))
                }
                graph.servers.forEach { server -> add(outbound(server, ProfileTags.server(server.id), options.fragment)) }
                graph.groups.forEach { (group, members) -> add(groupOutbound(group, members)) }
                addJsonObject {
                    put("type", "direct")
                    put("tag", TAG_DIRECT)
                }
                addJsonObject {
                    put("type", "block")
                    put("tag", TAG_BLOCK)
                }
            }

            putJsonObject("route") {
                putJsonArray("rules") {
                    // Сниффинг с 1.12 — действие правила; первым он нужен, чтобы
                    // доменные правила работали и для соединений по голому IP.
                    addJsonObject { put("action", "sniff") }
                    if (tun) {
                        addJsonObject {
                            put("protocol", "dns")
                            put("action", "hijack-dns")
                        }
                    }
                    rules.forEach { rule -> routeRule(rule, graph)?.let { add(it) } }
                }
                put("final", TAG_PROXY)
                // DNS-правила говорят на том же диалекте: `geosite:` там тоже
                // требует объявленного rule-set, иначе ядро не стартует.
                // Наборы качаются через прокси: без них ядро не стартует, а
                // raw.githubusercontent.com напрямую доступен не везде.
                // Скачанное хранит cache_file, и следующие старты сеть не ждут.
                val sets = RuleSetCatalog.derive(
                    rules = rules,
                    dnsRules = dnsSettings?.rules.orEmpty(),
                    explicit = options.ruleSets,
                    downloadDetour = TAG_PROXY,
                ).mapNotNull { it.json() }
                if (sets.isNotEmpty()) put("rule_set", JsonArray(sets))
                // С 1.12 выходу с доменным адресом нужен резолвер по умолчанию.
                if (dnsSettings != null) put("default_domain_resolver", dnsSettings.bootstrapTag)
                // Не привязываем сокеты к конкретному интерфейсу: на Android
                // SO_BINDTODEVICE без root часто даёт "dial rmnet_data0: i/o timeout".
                // Приложение и так исключено из собственного VPN, поэтому обычный
                // маршрут ОС — правильный путь наружу.
                put("auto_detect_interface", false)
            }

            if (tun) {
                putJsonObject("experimental") {
                    // Кэш rule-set и выборов селекторов. Только у туннеля: два
                    // экземпляра на одном bbolt-файле ждали бы блокировку.
                    putJsonObject("cache_file") { put("enabled", true) }
                    if (clashApi) {
                        putJsonObject("clash_api") {
                            put("external_controller", "127.0.0.1:9090")
                            put("default_mode", "rule")
                            put("secret", options.clashSecret)
                        }
                    }
                }
            }
        }
    }

    /**
     * Одно правило как route-правило sing-box, или null, если матчить нечего.
     * Цель, выход которой не построен, падает на `proxy`: правило просило
     * прокси, не хватает лишь названного узла или группы.
     */
    fun routeRule(rule: RoutingRule, graph: OutboundGraph? = null): JsonObject? {
        if (!rule.enabled || !rule.hasMatcher) return null
        val tag = graph?.tagFor(rule.target)
            ?: if (rule.target.needsProfile) TAG_PROXY else rule.target.tag()
        val ruleSetTags = rule.ruleSets.toMutableList()
        val sourceSide = rule.direction == RuleDirection.SOURCE
        val matchers = buildJsonObject {
            val domains = MatcherSyntax.domains(rule.domains)
            stringArray("domain", domains.exact)
            stringArray("domain_suffix", domains.suffix)
            stringArray("domain_keyword", domains.keyword)
            stringArray("domain_regex", domains.regex)
            ruleSetTags += domains.ruleSetTags

            val ips = MatcherSyntax.ips(rule.ips)
            stringArray(if (sourceSide) "source_ip_cidr" else "ip_cidr", ips.cidrs)
            if (ips.isPrivate) put(if (sourceSide) "source_ip_is_private" else "ip_is_private", true)
            ruleSetTags += ips.ruleSetTags
            if (ips.ruleSetTags.isNotEmpty() && sourceSide) put("rule_set_ip_cidr_match_source", true)

            val (ports, ranges) = MatcherSyntax.ports(rule.port)
            if (ports.isNotEmpty()) {
                putJsonArray(if (sourceSide) "source_port" else "port") { ports.forEach { add(it) } }
            }
            stringArray(if (sourceSide) "source_port_range" else "port_range", ranges)

            // На Android процесс — это пакет приложения.
            stringArray("package_name", rule.processNames)
            if (rule.network.isNotBlank()) put("network", rule.network)
            stringArray("protocol", rule.protocols)
            if (rule.clashMode.isNotBlank()) put("clash_mode", rule.clashMode)
            stringArray("rule_set", ruleSetTags.distinct())
        }
        // Всё выше могло не дать ни одного матчера (только `ext:`-домены, например).
        if (matchers.isEmpty()) return null
        return JsonObject(
            matchers.toMutableMap().apply {
                if (rule.invert) put("invert", JsonPrimitive(true))
                put("outbound", JsonPrimitive(tag))
            },
        )
    }

    private fun dnsRule(rule: DnsRule): JsonObject? {
        val matchers = buildJsonObject {
            val domains = MatcherSyntax.domains(rule.domains)
            stringArray("domain", domains.exact)
            stringArray("domain_suffix", domains.suffix)
            stringArray("domain_keyword", domains.keyword)
            stringArray("domain_regex", domains.regex)
            stringArray("package_name", rule.processNames)
            stringArray("rule_set", (rule.ruleSets + domains.ruleSetTags).distinct())
        }
        if (matchers.isEmpty()) return null
        if (!rule.reject && rule.serverTag.isBlank()) return null
        return JsonObject(
            matchers.toMutableMap().apply {
                if (rule.invert) put("invert", JsonPrimitive(true))
                if (rule.reject) {
                    put("action", JsonPrimitive("reject"))
                } else {
                    put("server", JsonPrimitive(rule.serverTag))
                }
            },
        )
    }

    private fun groupOutbound(group: ServerGroup, members: List<ProxyConfig>): JsonObject = buildJsonObject {
        val tags = members.map { ProfileTags.server(it.id) }
        put("tag", ProfileTags.group(group.id))
        putJsonArray("outbounds") { tags.forEach { add(it) } }
        when (group.kind) {
            GroupKind.SELECTOR -> {
                put("type", "selector")
                group.selectedId?.let(ProfileTags::server)?.takeIf { it in tags }?.let { put("default", it) }
            }
            GroupKind.URLTEST -> {
                put("type", "urltest")
                put("url", group.testUrl)
                put("interval", group.interval)
                put("tolerance", group.tolerance)
            }
        }
    }

    /** DNS-детур на узел или группу, которых нет в конфиге, уходит в `proxy`. */
    private fun OutboundGraph.detourTag(detour: String): String =
        if ((detour.startsWith("srv-") || detour.startsWith("grp-")) && detour !in tags) TAG_PROXY else detour

    private fun JsonObjectBuilder.stringArray(key: String, values: List<String>) {
        if (values.isNotEmpty()) putJsonArray(key) { values.forEach { add(it) } }
    }

    private fun logJson(options: TunnelOptions): JsonObject = buildJsonObject {
        put("level", options.logLevel)
        put("timestamp", true)
        options.logFile?.let { put("output", it) }
    }

    /**
     * Выход узла. [fragment] — та же защита от DPI, что finalmask у Xray:
     * ClientHello режется на куски, и цельный SNI не виден. Без неё на
     * сетях с DPI соединение сбрасывается ещё на рукопожатии.
     */
    internal fun outbound(cfg: ProxyConfig, tag: String, fragment: Boolean = false): JsonObject {
        val base = baseOutbound(cfg, tag)
        if (!fragment) return base
        val tls = base["tls"] as? JsonObject ?: return base
        if ((tls["enabled"] as? JsonPrimitive)?.booleanOrNull != true) return base
        return JsonObject(base + ("tls" to JsonObject(tls + ("fragment" to JsonPrimitive(true)))))
    }

    private fun baseOutbound(cfg: ProxyConfig, tag: String): JsonObject = buildJsonObject {
        put("tag", tag)

        when (cfg.proto) {
            ProxyProtocol.VLESS -> {
                put("type", "vless")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("uuid", cfg.id)
                cfg.flow?.takeIf { it.isNotBlank() }?.let { put("flow", it) }

                // Transport
                when (cfg.network) {
                    TransportNetwork.WS -> {
                        putJsonObject("transport") {
                            put("type", "ws")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let {
                                putJsonObject("headers") {
                                    put("Host", it)
                                }
                            }
                        }
                    }
                    TransportNetwork.GRPC -> {
                        putJsonObject("transport") {
                            put("type", "grpc")
                            cfg.serviceName?.let { put("service_name", it) }
                        }
                    }
                    TransportNetwork.HTTP -> {
                        putJsonObject("transport") {
                            put("type", "http")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let { putJsonArray("host") { add(it) } }
                        }
                    }
                    TransportNetwork.XHTTP -> {
                        // sing-box doesn't support xhttp yet, fallback to tcp
                        putJsonObject("transport") {
                            put("type", "http")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let { putJsonArray("host") { add(it) } }
                        }
                    }
                    else -> {
                        // TCP or unsupported
                    }
                }

                // TLS
                when (cfg.security) {
                    StreamSecurity.TLS -> {
                        putJsonObject("tls") {
                            put("enabled", true)
                            cfg.sni?.let { put("server_name", it) }
                            cfg.alpn?.let { alpn ->
                                putJsonArray("alpn") { alpn.forEach { add(it) } }
                            }
                            cfg.allowInsecure.let { put("insecure", it) }
                            // Отпечаток браузера: sing-box включает uTLS только явно.
                            cfg.fingerprint?.let { fp ->
                                putJsonObject("utls") {
                                    put("enabled", true)
                                    put("fingerprint", fp)
                                }
                            }
                        }
                    }
                    StreamSecurity.REALITY -> {
                        putJsonObject("tls") {
                            put("enabled", true)
                            put("reality", buildJsonObject {
                                put("enabled", true)
                                cfg.publicKey?.let { put("public_key", it) }
                                cfg.shortId?.let { put("short_id", it) }
                            })
                            cfg.sni?.let { put("server_name", it) }
                            // REALITY без uTLS sing-box не запускает вообще:
                            // "uTLS is required by reality client".
                            putJsonObject("utls") {
                                put("enabled", true)
                                put("fingerprint", cfg.fingerprint ?: "chrome")
                            }
                            put("insecure", cfg.allowInsecure)
                        }
                    }
                    else -> {
                        // No TLS
                    }
                }
            }

            ProxyProtocol.VMESS -> {
                put("type", "vmess")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("uuid", cfg.id)
                put("alter_id", cfg.alterId ?: 0)

                // Transport
                when (cfg.network) {
                    TransportNetwork.WS -> {
                        putJsonObject("transport") {
                            put("type", "ws")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let {
                                putJsonObject("headers") {
                                    put("Host", it)
                                }
                            }
                        }
                    }
                    TransportNetwork.GRPC -> {
                        putJsonObject("transport") {
                            put("type", "grpc")
                            cfg.serviceName?.let { put("service_name", it) }
                        }
                    }
                    TransportNetwork.HTTP -> {
                        putJsonObject("transport") {
                            put("type", "http")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let { putJsonArray("host") { add(it) } }
                        }
                    }
                    else -> {
                        // TCP or unsupported transport
                    }
                }

                // TLS
                if (cfg.security == StreamSecurity.TLS) {
                    putJsonObject("tls") {
                        put("enabled", true)
                        cfg.sni?.let { put("server_name", it) }
                        cfg.alpn?.let { alpn ->
                            putJsonArray("alpn") { alpn.forEach { add(it) } }
                        }
                        cfg.allowInsecure?.let { put("insecure", it) }
                        cfg.fingerprint?.let { fp ->
                            putJsonObject("utls") {
                                put("enabled", true)
                                put("fingerprint", fp)
                            }
                        }
                    }
                }
            }

            ProxyProtocol.TROJAN -> {
                put("type", "trojan")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("password", cfg.password ?: "")

                // Transport
                when (cfg.network) {
                    TransportNetwork.WS -> {
                        putJsonObject("transport") {
                            put("type", "ws")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let {
                                putJsonObject("headers") {
                                    put("Host", it)
                                }
                            }
                        }
                    }
                    TransportNetwork.GRPC -> {
                        putJsonObject("transport") {
                            put("type", "grpc")
                            cfg.serviceName?.let { put("service_name", it) }
                        }
                    }
                    TransportNetwork.HTTP -> {
                        putJsonObject("transport") {
                            put("type", "http")
                            cfg.path?.let { put("path", it) }
                            cfg.host?.let { putJsonArray("host") { add(it) } }
                        }
                    }
                    else -> {
                        // TCP or unsupported transport: no transport block
                    }
                }

                // TLS
                putJsonObject("tls") {
                    put("enabled", true)
                    cfg.sni?.let { put("server_name", it) }
                    cfg.alpn?.let { alpn ->
                        putJsonArray("alpn") { alpn.forEach { add(it) } }
                    }
                    cfg.allowInsecure?.let { put("insecure", it) }
                }
            }

            ProxyProtocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("method", cfg.method ?: "chacha20-ietf-poly1305")
                put("password", cfg.password ?: "")
            }

            ProxyProtocol.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("password", cfg.password ?: "")
                cfg.obfs?.takeIf { it.isNotBlank() }?.let { obfs ->
                    putJsonObject("obfs") {
                        put("type", obfs)
                        cfg.obfsPassword?.let { put("password", it) }
                    }
                }
                cfg.upMbps?.let { put("up_mbps", it) }
                cfg.downMbps?.let { put("down_mbps", it) }
                putJsonObject("tls") { tlsFields(cfg) }
            }

            ProxyProtocol.TUIC -> {
                put("type", "tuic")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("uuid", cfg.uuid ?: "")
                put("password", cfg.password ?: "")
                cfg.congestionControl?.let { put("congestion_control", it) }
                cfg.udpRelayMode?.let { put("udp_relay_mode", it) }
                putJsonObject("tls") { tlsFields(cfg) }
            }

            ProxyProtocol.ANYTLS -> {
                put("type", "anytls")
                put("server", cfg.address)
                put("server_port", cfg.port)
                put("password", cfg.password ?: "")
                putJsonObject("tls") { tlsFields(cfg) }
            }

            else -> {
                // Fallback: direct
                put("type", "direct")
            }
        }
    }

    /** Общий TLS-блок для QUIC-протоколов и AnyTLS. */
    private fun JsonObjectBuilder.tlsFields(cfg: ProxyConfig) {
        put("enabled", true)
        cfg.sni?.let { put("server_name", it) }
        cfg.alpn?.let { alpn ->
            putJsonArray("alpn") { alpn.forEach { add(it) } }
        }
        if (cfg.allowInsecure) put("insecure", true)
        cfg.fingerprint?.let { fingerprint ->
            putJsonObject("utls") {
                put("enabled", true)
                put("fingerprint", fingerprint)
            }
        }
    }
}
