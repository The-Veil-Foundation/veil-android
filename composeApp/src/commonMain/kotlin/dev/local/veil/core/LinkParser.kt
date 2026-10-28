package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class LinkParseException(message: String) : Exception(message)

/**
 * Разбор share-ссылок в [ProxyConfig].
 *
 * Порт `LinkParser.swift` из veil-macos: те же формы ссылок, те же допущения
 * (однократное percent-декодирование credential, trojan по умолчанию TLS,
 * `sni` наследуется от `host`).
 */
object LinkParser {

    fun parse(raw: String): ProxyConfig {
        val line = raw.trim()
        val scheme = line.substringBefore("://", "").lowercase()
        if (scheme.isEmpty() || !line.contains("://")) {
            throw LinkParseException("Malformed link: $line")
        }
        return when (scheme) {
            "vless" -> parseVless(line)
            "vmess" -> parseVmess(line)
            "trojan" -> parseTrojan(line)
            "ss" -> parseShadowsocks(line)
            "hysteria2", "hy2" -> parseHysteria2(line)
            "tuic" -> parseTuic(line)
            "anytls" -> parseAnyTls(line)
            "wireguard", "wg" -> parseWireGuard(line)
            else -> throw LinkParseException("Unsupported link scheme: $scheme")
        }
    }

    /** Разбирает список ссылок построчно, молча пропуская битые. */
    fun parseMany(text: String): List<ProxyConfig> =
        text.lineSequence().mapNotNull { runCatching { parse(it) }.getOrNull() }.toList()

    /* ---------------- vless ---------------- */

    private fun parseVless(line: String): ProxyConfig {
        val uri = parseRawUri(line)
        val uuid = credential(uri.userInfo) ?: throw LinkParseException("Malformed link: $line")
        val q = uri.query

        return ProxyConfig(
            name = uri.fragment.ifBlank { uri.host },
            proto = ProxyProtocol.VLESS,
            address = uri.host,
            port = uri.port,
            uuid = uuid,
            encryption = q["encryption"] ?: "none",
            flow = q["flow"],
        ).let { applySecurity(applyTransport(it, q), q) }
    }

    /* ---------------- vmess ---------------- */

    private fun parseVmess(line: String): ProxyConfig {
        val b64 = line.removePrefix("vmess://")
        val json: JsonObject = runCatching {
            Json.parseToJsonElement(base64Decode(b64)) as JsonObject
        }.getOrNull() ?: throw LinkParseException("Malformed link: $line")

        fun str(key: String): String? = json[key]?.jsonPrimitive?.contentOrNull

        val host = str("add") ?: throw LinkParseException("Malformed link: $line")
        val port = str("port")?.toIntOrNull() ?: throw LinkParseException("Malformed link: $line")
        val id = str("id") ?: throw LinkParseException("Malformed link: $line")

        val tls = (str("tls") ?: "").lowercase()
        val security = when {
            tls.contains("reality") -> StreamSecurity.REALITY
            tls == "tls" -> StreamSecurity.TLS
            else -> StreamSecurity.NONE
        }

        return ProxyConfig(
            name = str("ps") ?: host,
            proto = ProxyProtocol.VMESS,
            address = host,
            port = port,
            uuid = id,
            alterId = str("aid")?.toIntOrNull() ?: 0,
            network = TransportNetwork.fromWire(str("net")),
            path = str("path"),
            host = str("host"),
            serviceName = str("path"),
            security = security,
            sni = str("sni") ?: str("host"),
            alpn = str("alpn")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() },
        )
    }

    /* ---------------- trojan ---------------- */

    private fun parseTrojan(line: String): ProxyConfig {
        val uri = parseRawUri(line)
        val pwd = credential(uri.userInfo) ?: throw LinkParseException("Malformed link: $line")
        val q = uri.query

        var cfg = ProxyConfig(
            name = uri.fragment.ifBlank { uri.host },
            proto = ProxyProtocol.TROJAN,
            address = uri.host,
            port = uri.port,
            password = pwd,
        )
        cfg = applyTransport(cfg, q)
        // Trojan по умолчанию TLS, если ссылка не сказала иначе.
        if (q["security"] == null && q["tls"] == null) cfg = cfg.copy(security = StreamSecurity.TLS)
        return applySecurity(cfg, q)
    }

    /* ---------------- shadowsocks ---------------- */

    private fun parseShadowsocks(line: String): ProxyConfig {
        val uri = runCatching { parseRawUri(line) }.getOrNull()
        val name = uri?.fragment?.ifBlank { null }

        var body = line.removePrefix("ss://")
        body = body.substringBefore('#')
        body = body.substringBefore('?')

        var method: String? = null
        var password: String? = null
        val host: String
        val port: Int

        val at = body.indexOf('@')
        if (at >= 0) {
            // SIP002
            val userInfo = body.substring(0, at)
            val hostPart = body.substring(at + 1)
            val decoded = runCatching { base64Decode(userInfo) }.getOrNull() ?: percentDecode(userInfo)
            val sep = decoded.indexOf(':')
            if (sep >= 0) {
                method = decoded.substring(0, sep)
                password = decoded.substring(sep + 1)
            }
            val hp = splitHostPort(hostPart)
            host = hp.first
            port = hp.second
        } else {
            // Legacy base64(method:password@host:port)
            val decoded = runCatching { base64Decode(body) }.getOrNull()
                ?: throw LinkParseException("Malformed link: $line")
            val atIdx = decoded.lastIndexOf('@')
            if (atIdx < 0) throw LinkParseException("Malformed link: $line")
            val cred = decoded.substring(0, atIdx)
            val sep = cred.indexOf(':')
            if (sep >= 0) {
                method = cred.substring(0, sep)
                password = cred.substring(sep + 1)
            }
            val hp = splitHostPort(decoded.substring(atIdx + 1))
            host = hp.first
            port = hp.second
        }

        val m = method ?: throw LinkParseException("Malformed link: $line")
        val pw = password ?: throw LinkParseException("Malformed link: $line")

        return ProxyConfig(
            name = name ?: host,
            proto = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            method = m,
            password = pw,
        )
    }

    /* ---------------- hysteria2 (не поддерживается Xray) ---------------- */

    private fun parseHysteria2(line: String): ProxyConfig {
        val uri = parseRawUri(line)
        val q = uri.query

        val password = credential(uri.userInfo)
            ?: q["auth"] ?: q["auth_str"] ?: q["auth-str"] ?: q["password"]

        return ProxyConfig(
            name = uri.fragment.ifBlank { uri.host },
            proto = ProxyProtocol.HYSTERIA2,
            address = uri.host,
            port = uri.port,
            password = password,
            security = StreamSecurity.TLS,
            sni = q["sni"] ?: q["peer"],
            allowInsecure = q["insecure"] == "1" || q["insecure"] == "true",
            alpn = q["alpn"]?.let { percentDecode(it) }?.split(',')?.map { it.trim() },
            obfs = q["obfs"],
            obfsPassword = (q["obfs-password"] ?: q["obfs_password"])?.let { percentDecode(it) },
            upMbps = (q["upmbps"] ?: q["up"])?.toIntOrNull(),
            downMbps = (q["downmbps"] ?: q["down"])?.toIntOrNull(),
            fingerprint = q["fp"],
        )
    }

    /* ---------------- tuic (не поддерживается Xray) ---------------- */

    private fun parseTuic(line: String): ProxyConfig {
        val uri = parseRawUri(line)
        val q = uri.query
        val userInfo = percentDecode(uri.userInfo)
        val sep = userInfo.indexOf(':')

        return ProxyConfig(
            name = uri.fragment.ifBlank { uri.host },
            proto = ProxyProtocol.TUIC,
            address = uri.host,
            port = uri.port,
            uuid = if (sep >= 0) userInfo.substring(0, sep) else userInfo.ifEmpty { null },
            password = if (sep >= 0) userInfo.substring(sep + 1) else null,
            security = StreamSecurity.TLS,
            sni = q["sni"] ?: q["peer"],
            allowInsecure = q["allow_insecure"] in setOf("1", "true") || q["insecure"] in setOf("1", "true"),
            alpn = q["alpn"]?.let { percentDecode(it) }?.split(',')?.map { it.trim() },
            congestionControl = q["congestion_control"] ?: q["congestion"],
            udpRelayMode = q["udp_relay_mode"],
            fingerprint = q["fp"],
        )
    }

    /* ---------------- anytls (не поддерживается Xray) ---------------- */

    private fun parseAnyTls(line: String): ProxyConfig {
        val uri = parseRawUri(line)
        val q = uri.query

        return ProxyConfig(
            name = uri.fragment.ifBlank { uri.host },
            proto = ProxyProtocol.ANYTLS,
            address = uri.host,
            port = uri.port,
            password = credential(uri.userInfo),
            security = StreamSecurity.TLS,
            sni = q["sni"] ?: q["peer"] ?: q["host"],
            allowInsecure = q["insecure"] in setOf("1", "true") ||
                q["allowInsecure"] in setOf("1", "true"),
            alpn = q["alpn"]?.let { percentDecode(it) }?.split(',')?.map { it.trim() },
            fingerprint = q["fp"],
        )
    }

    /* ---------------- wireguard ---------------- */

    private fun parseWireGuard(line: String): ProxyConfig {
        val uri = parseRawUri(line)
        val q = uri.query

        val addresses = (q["address"] ?: q["ip"])?.let { percentDecode(it) }
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val allowed = (q["allowedips"] ?: q["allowed_ips"])?.let { percentDecode(it) }
            ?.let { splitList(it) }
        val reserved = q["reserved"]?.let { percentDecode(it) }
            ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }
            ?.takeIf { it.size == 3 }

        return ProxyConfig(
            name = uri.fragment.ifBlank { uri.host },
            proto = ProxyProtocol.WIREGUARD,
            address = uri.host,
            port = uri.port,
            privateKey = credential(uri.userInfo) ?: q["privatekey"] ?: q["secretkey"],
            peerPublicKey = q["publickey"] ?: q["public_key"] ?: q["peer_public_key"],
            presharedKey = q["presharedkey"] ?: q["pre_shared_key"],
            localAddresses = addresses,
            allowedIPs = allowed,
            mtu = q["mtu"]?.toIntOrNull(),
            reserved = reserved,
        )
    }

    /** Разбор вставленного wg-quick `.conf`. */
    fun parseWireGuardConf(text: String, name: String? = null): ProxyConfig? {
        var privateKey: String? = null
        var addresses: List<String> = emptyList()
        var mtu: Int? = null
        var peerPublicKey: String? = null
        var presharedKey: String? = null
        var allowedIPs: List<String> = emptyList()
        var endpointHost: String? = null
        var endpointPort: Int? = null

        for (rawLine in text.lineSequence()) {
            val l = rawLine.trim()
            if (l.startsWith("#") || l.startsWith("[")) continue
            val eq = l.indexOf('=')
            if (eq < 0) continue
            val key = l.substring(0, eq).trim().lowercase()
            val value = l.substring(eq + 1).trim()

            when (key) {
                "privatekey" -> privateKey = value
                "address" -> addresses = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                "mtu" -> mtu = value.toIntOrNull()
                "publickey" -> peerPublicKey = value
                "presharedkey" -> presharedKey = value
                "allowedips" -> allowedIPs = splitList(value)
                "endpoint" -> runCatching { splitHostPort(value) }.getOrNull()?.let {
                    endpointHost = it.first
                    endpointPort = it.second
                }
            }
        }

        val host = endpointHost ?: return null
        val port = endpointPort ?: return null
        if (privateKey == null || peerPublicKey == null) return null

        return ProxyConfig(
            name = name ?: host,
            proto = ProxyProtocol.WIREGUARD,
            address = host,
            port = port,
            privateKey = privateKey,
            peerPublicKey = peerPublicKey,
            presharedKey = presharedKey,
            localAddresses = addresses.ifEmpty { null },
            allowedIPs = allowedIPs.ifEmpty { null },
            mtu = mtu,
        )
    }

    /* ---------------- helpers ---------------- */

    private fun splitList(value: String): List<String> =
        value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun applyTransport(cfg: ProxyConfig, q: Map<String, String>): ProxyConfig {
        var out = cfg.copy(
            network = if (q["type"] != null || q["net"] != null) {
                TransportNetwork.fromWire(q["type"] ?: q["net"])
            } else {
                cfg.network
            },
            path = q["path"]?.let { percentDecode(it) } ?: q["path"] ?: cfg.path,
            host = q["host"]?.let { percentDecode(it) } ?: q["host"] ?: cfg.host,
            serviceName = q["serviceName"]?.let { percentDecode(it) } ?: q["serviceName"] ?: cfg.serviceName,
            xhttpMode = q["mode"] ?: cfg.xhttpMode,
        )

        q["extra"]?.let { percentDecode(it) }?.takeIf { it.isNotEmpty() }?.let {
            out = out.copy(xhttpExtra = it)
        }

        val pad = q["x_padding_bytes"] ?: q["xPaddingBytes"]
        if (pad != null) {
            out = out.copy(xPaddingBytes = percentDecode(pad))
        } else {
            val extra = out.xhttpExtra
            if (extra != null) {
                val parsed = runCatching {
                    Json.parseToJsonElement(extra) as JsonObject
                }.getOrNull()
                parsed?.get("xPaddingBytes")?.jsonPrimitive?.contentOrNull?.let {
                    out = out.copy(xPaddingBytes = it)
                }
            }
        }
        return out
    }

    private fun applySecurity(cfg: ProxyConfig, q: Map<String, String>): ProxyConfig {
        var out = cfg
        if (q["security"] != null) {
            out = out.copy(security = StreamSecurity.fromWire(q["security"]))
        }
        out = out.copy(
            sni = q["sni"] ?: q["peer"] ?: out.host ?: cfg.sni,
            fingerprint = q["fp"] ?: out.fingerprint,
            alpn = q["alpn"]?.let { percentDecode(it) }?.split(',')?.map { it.trim() } ?: out.alpn,
            allowInsecure = q["allowInsecure"] in setOf("1", "true"),
            publicKey = q["pbk"] ?: out.publicKey,
            shortId = q["sid"] ?: out.shortId,
            spiderX = q["spx"]?.let { percentDecode(it) } ?: out.spiderX,
        )
        return out
    }

    /** Credential из userinfo, декодируется ровно один раз; пустое → null. */
    private fun credential(percentEncoded: String?): String? {
        if (percentEncoded.isNullOrEmpty()) return null
        val decoded = percentDecode(percentEncoded)
        return decoded.ifEmpty { null }
    }
}
