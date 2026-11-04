package dev.local.veil.core

import kotlinx.serialization.Serializable

/* ------------------------------------------------------------------ */
/* DNS — порт DNSSettings.swift из veil-ios (упрощённый под Android)    */
/* ------------------------------------------------------------------ */

@Serializable
enum class DnsServerKind {
    LOCAL,
    UDP,
    TCP,
    TLS,
    HTTPS,
    QUIC,
    H3,
    ;

    val title: String
        get() = when (this) {
            LOCAL -> "Системный"
            UDP -> "UDP"
            TCP -> "TCP"
            TLS -> "DNS over TLS"
            HTTPS -> "DNS over HTTPS"
            QUIC -> "DNS over QUIC"
            H3 -> "DNS over HTTP/3"
        }

    val needsServer: Boolean get() = this != LOCAL
    val needsPath: Boolean get() = this == HTTPS || this == H3

    /** Схема для Xray `dns.servers[].address`. */
    fun address(server: String): String = when (this) {
        LOCAL -> "localhost"
        UDP -> server
        TCP -> "tcp://$server"
        TLS -> "tls://$server"
        HTTPS -> "https://$server"
        QUIC -> "quic://$server"
        H3 -> "h3://$server"
    }
}

@Serializable
data class DnsServerEntry(
    val id: String = newId(),
    val tag: String,
    val kind: DnsServerKind = DnsServerKind.UDP,
    val server: String = "",
    val port: Int? = null,
    val path: String = "",
    val detour: String = "",
    val strategy: String = "",
)

@Serializable
data class DnsRule(
    val id: String = newId(),
    val name: String = "",
    val enabled: Boolean = true,
    val serverTag: String = "",
    val domains: List<String> = emptyList(),
    val reject: Boolean = false,
) {
    val hasMatcher: Boolean get() = domains.isNotEmpty()
}

@Serializable
data class DnsSettings(
    val enabled: Boolean = true,
    val servers: List<DnsServerEntry> = defaultServers(),
    val rules: List<DnsRule> = defaultRules(),
    val finalTag: String = "dns-remote",
    /** prefer_ipv4 | prefer_ipv6 | ipv4_only | ipv6_only */
    val strategy: String = "prefer_ipv4",
    val reverseMapping: Boolean = true,
    val fakeIpEnabled: Boolean = false,
    val fakeIpv4Range: String = "198.18.0.0/15",
    val fakeIpv6Range: String = "fc00::/18",
) {
    val bootstrapTag: String
        get() = servers.firstOrNull { it.tag == "dns-bootstrap" }?.tag
            ?: servers.firstOrNull { it.detour == "direct" }?.tag
            ?: servers.firstOrNull()?.tag
            ?: "dns-local"

    /** Чинит полупустые записи, из-за которых ядро не стартует. */
    fun sanitized(): DnsSettings {
        val repaired = servers.mapNotNull { entry ->
            var e = entry
            if (e.tag == "dns-bootstrap") {
                if (!e.kind.needsServer) e = e.copy(kind = DnsServerKind.UDP)
                if (e.server.isBlank()) e = e.copy(server = "1.1.1.1")
                e = e.copy(detour = "direct")
            }
            if (e.kind.needsServer && e.server.isBlank()) null else e
        }.toMutableList()

        if (repaired.none { it.tag == "dns-bootstrap" }) {
            repaired += DnsServerEntry(
                tag = "dns-bootstrap",
                kind = DnsServerKind.UDP,
                server = "1.1.1.1",
                detour = "direct",
            )
        }
        if (repaired.none { it.tag != "dns-bootstrap" }) {
            repaired.add(
                0,
                DnsServerEntry(
                    tag = "dns-remote",
                    kind = DnsServerKind.HTTPS,
                    server = "1.1.1.1",
                    path = "/dns-query",
                    detour = "proxy",
                ),
            )
        }
        val tags = repaired.map { it.tag }.toSet()
        var final = finalTag
        if (final.isEmpty() || final !in tags || final == "dns-bootstrap") {
            final = if ("dns-remote" in tags) "dns-remote" else repaired.firstOrNull { it.tag != "dns-bootstrap" }?.tag.orEmpty()
        }
        return copy(
            servers = repaired,
            finalTag = final,
            rules = rules.filter { it.reject || it.serverTag in tags },
        )
    }

    companion object {
        fun defaultServers(): List<DnsServerEntry> = listOf(
            DnsServerEntry(
                tag = "dns-remote",
                kind = DnsServerKind.HTTPS,
                server = "1.1.1.1",
                path = "/dns-query",
                detour = "proxy",
            ),
            DnsServerEntry(tag = "dns-local", kind = DnsServerKind.LOCAL, detour = "direct"),
            DnsServerEntry(
                tag = "dns-bootstrap",
                kind = DnsServerKind.UDP,
                server = "1.1.1.1",
                detour = "direct",
            ),
        )

        fun defaultRules(): List<DnsRule> = listOf(
            DnsRule(
                name = "Локальные имена",
                serverTag = "dns-local",
                domains = listOf("domain:local", "domain:lan", "domain:internal", "domain:home.arpa"),
            ),
        )
    }
}
