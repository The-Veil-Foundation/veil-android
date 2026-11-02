package dev.local.veil.core

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Собирает share-ссылку узла — обратная операция к [LinkParser].
 * Для узлов из готового конфига Xray (rawConfigJson) и для протоколов,
 * которых ссылка не покрывает, возвращает null.
 */
object ShareLink {

    fun encode(cfg: ProxyConfig): String? {
        if (cfg.rawConfigJson != null) return null
        return when (cfg.proto) {
            ProxyProtocol.VLESS -> vless(cfg)
            ProxyProtocol.VMESS -> vmess(cfg)
            ProxyProtocol.TROJAN -> trojan(cfg)
            ProxyProtocol.SHADOWSOCKS -> shadowsocks(cfg)
            else -> null
        }
    }

    private fun vless(cfg: ProxyConfig): String? {
        val id = cfg.uuid ?: return null
        val q = query(
            "encryption" to (cfg.encryption ?: "none"),
            "flow" to cfg.flow,
            *streamParams(cfg),
        )
        return "vless://${pct(id)}@${hostPart(cfg.address)}:${cfg.port}?$q#${pct(cfg.name)}"
    }

    private fun trojan(cfg: ProxyConfig): String? {
        val password = cfg.password ?: return null
        val q = query(*streamParams(cfg))
        return "trojan://${pct(password)}@${hostPart(cfg.address)}:${cfg.port}?$q#${pct(cfg.name)}"
    }

    private fun vmess(cfg: ProxyConfig): String? {
        val id = cfg.uuid ?: return null
        val json = buildJsonObject {
            put("v", "2")
            put("ps", cfg.name)
            put("add", cfg.address)
            put("port", cfg.port.toString())
            put("id", id)
            put("aid", (cfg.alterId ?: 0).toString())
            put("net", cfg.network.wire)
            put("type", "none")
            put("host", cfg.host.orEmpty())
            put("path", cfg.path ?: cfg.serviceName ?: "")
            put(
                "tls",
                when (cfg.security) {
                    StreamSecurity.TLS -> "tls"
                    StreamSecurity.REALITY -> "reality"
                    else -> ""
                },
            )
            put("sni", cfg.sni.orEmpty())
            put("alpn", cfg.alpn?.joinToString(",").orEmpty())
        }
        return "vmess://" + base64Encode(json.toString().encodeToByteArray(), urlSafe = false, pad = true)
    }

    private fun shadowsocks(cfg: ProxyConfig): String? {
        val method = cfg.method ?: return null
        val password = cfg.password ?: return null
        val userInfo = base64Encode("$method:$password".encodeToByteArray(), urlSafe = true, pad = false)
        return "ss://$userInfo@${hostPart(cfg.address)}:${cfg.port}#${pct(cfg.name)}"
    }

    /** Транспорт и защита канала: общие для vless и trojan параметры. */
    private fun streamParams(cfg: ProxyConfig): Array<Pair<String, String?>> = arrayOf(
        "type" to cfg.network.wire,
        "security" to cfg.security.wire,
        "sni" to cfg.sni,
        "fp" to cfg.fingerprint,
        "alpn" to cfg.alpn?.joinToString(","),
        "pbk" to cfg.publicKey,
        "sid" to cfg.shortId,
        "spx" to cfg.spiderX,
        "allowInsecure" to if (cfg.allowInsecure) "1" else null,
        "path" to cfg.path,
        "host" to cfg.host,
        "serviceName" to cfg.serviceName,
        "mode" to cfg.xhttpMode,
        "extra" to cfg.xhttpExtra,
    )

    private fun query(vararg pairs: Pair<String, String?>): String =
        pairs.filter { !it.second.isNullOrEmpty() }.joinToString("&") { "${it.first}=${pct(it.second!!)}" }

    private fun hostPart(address: String): String =
        if (':' in address && !address.startsWith("[")) "[$address]" else address

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    private const val HEX = "0123456789ABCDEF"

    private fun pct(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) {
                append(c.toChar())
            } else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 15])
            }
        }
    }

    private fun base64Encode(bytes: ByteArray, urlSafe: Boolean, pad: Boolean): String {
        val alphabet = if (urlSafe) {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        } else {
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        }
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            out.append(alphabet[b0 shr 2])
            out.append(alphabet[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) {
                out.append(alphabet[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            } else if (pad) {
                out.append('=')
            }
            if (b2 >= 0) {
                out.append(alphabet[b2 and 63])
            } else if (pad) {
                out.append('=')
            }
            i += 3
        }
        return out.toString()
    }
}
