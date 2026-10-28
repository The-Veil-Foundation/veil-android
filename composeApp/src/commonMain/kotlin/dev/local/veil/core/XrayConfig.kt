package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Куда ядро смотрит за трафиком. */
sealed interface XrayInbound {
    data class Tun(val name: String = "veil0", val mtu: Int = 1500) : XrayInbound
    data class LocalProxy(val socks: Int = 10808, val http: Int = 10809) : XrayInbound
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
    val bypassRu: Boolean = false,
)

/**
 * Собирает Xray-JSON из [ProxyConfig].
 *
 * Порт `XrayConfigBuilder.swift`. Отличия только в том, что здесь нет ветки
 * balancer/групп — она появится вместе с экраном групп.
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

        val hasDns = options.dnsServers.isNotEmpty()

        return buildJsonObject {
            put("log", logJson(options))
            put("inbounds", JsonArray(inboundList(inbound)))

            putJsonArray("outbounds") {
                add(outbound(cfg, TAG_PROXY, options))
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
                    // Приватные диапазоны перечислены явно: geoip.dat в APK нет,
                    // а `geoip:private` без него роняет весь конфиг.
                    addJsonObject {
                        put("type", "field")
                        putJsonArray("ip") {
                            add("10.0.0.0/8")
                            add("172.16.0.0/12")
                            add("192.168.0.0/16")
                            add("169.254.0.0/16")
                            add("127.0.0.0/8")
                            add("fc00::/7")
                            add("fe80::/10")
                            add("::1/128")
                        }
                        put("outboundTag", TAG_DIRECT)
                    }
                    if (options.bypassRu) {
                        // Домены .ru/.su/.рф идут напрямую: без geo-баз,
                        // чтобы конфиг не падал на отсутствующем geosite.dat.
                        addJsonObject {
                            put("type", "field")
                            putJsonArray("domain") {
                                add("domain:ru")
                                add("domain:su")
                                add("domain:xn--p1ai")
                            }
                            put("outboundTag", TAG_DIRECT)
                        }
                    }
                    addJsonObject {
                        put("type", "field")
                        put("network", "tcp,udp")
                        put("outboundTag", TAG_PROXY)
                    }
                }
            }

            if (hasDns) {
                putJsonObject("dns") {
                    putJsonArray("servers") { options.dnsServers.forEach { add(it) } }
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
        if (!mutable.containsKey("stats")) mutable["stats"] = JsonObject(emptyMap())
        if (!mutable.containsKey("policy")) mutable["policy"] = policyJson()
        return JsonObject(mutable)
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
                    put("auth", "noauth")
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
