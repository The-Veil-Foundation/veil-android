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
    const val TAG_DNS_OUT = "dns-out"

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
        return buildJsonObject {
            put("log", logJson(options))

            putJsonObject("dns") {
                putJsonArray("servers") {
                    options.dnsServers.forEach { dns ->
                        addJsonObject {
                            put("tag", "dns-$dns")
                            put("address", dns)
                        }
                    }
                }
                putJsonArray("rules") {
                    addJsonObject {
                        putJsonArray("geosite") {
                            add("category-ads-all")
                        }
                        put("server", "block")
                    }
                }
                put("strategy", if (options.ipv6) "prefer_ipv4" else "ipv4_only")
            }

            putJsonArray("inbounds") {
                when (inbound) {
                    is Inbound.Tun -> addJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", inbound.name)
                        put("inet4_address", "172.19.0.1/30")
                        if (options.ipv6) {
                            put("inet6_address", "fdfe:dcba:9876::1/126")
                        }
                        put("mtu", inbound.mtu)
                        put("auto_route", true)
                        put("strict_route", true)
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
                add(outbound(cfg, TAG_PROXY))
                addJsonObject {
                    put("type", "direct")
                    put("tag", TAG_DIRECT)
                }
                addJsonObject {
                    put("type", "block")
                    put("tag", TAG_BLOCK)
                }
                addJsonObject {
                    put("type", "dns")
                    put("tag", TAG_DNS_OUT)
                }
            }

            putJsonObject("route") {
                putJsonArray("rules") {
                    // DNS queries
                    addJsonObject {
                        put("protocol", "dns")
                        put("outbound", TAG_DNS_OUT)
                    }

                    // Private networks
                    addJsonObject {
                        putJsonArray("ip_cidr") {
                            PRIVATE_NETWORKS.forEach { add(it) }
                        }
                        put("outbound", TAG_DIRECT)
                    }

                    // Russian domains bypass
                    if (options.bypassRu) {
                        addJsonObject {
                            putJsonArray("domain_suffix") {
                                add(".ru")
                                add(".su")
                                add(".рф")
                            }
                            put("outbound", TAG_DIRECT)
                        }
                    }

                    // Default: everything through proxy
                    addJsonObject {
                        put("outbound", TAG_PROXY)
                    }
                }
                put("auto_detect_interface", true)
            }
        }
    }

    private fun logJson(options: TunnelOptions): JsonObject = buildJsonObject {
        put("level", options.logLevel)
        put("timestamp", true)
        options.logFile?.let { put("output", it) }
    }

    private fun outbound(cfg: ProxyConfig, tag: String): JsonObject = buildJsonObject {
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

    private val PRIVATE_NETWORKS = listOf(
        "10.0.0.0/8",
        "100.64.0.0/10",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.0.0.0/24",
        "192.0.2.0/24",
        "192.168.0.0/16",
        "198.18.0.0/15",
        "198.51.100.0/24",
        "203.0.113.0/24",
        "224.0.0.0/3",
        "fc00::/7",
        "fe80::/10",
    )
}
