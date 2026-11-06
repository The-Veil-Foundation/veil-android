package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A node written out in the form the user wants to read or edit it in: a port
 * of NodeRepresentation from veil-macos. Each form reads back into a node.
 */
object NodeRepresentation {

    enum class Kind {
        /** `wg-quick` text with `[Interface]` and `[Peer]`. */
        WIREGUARD,
        /** The outbound its core will actually run. */
        JSON,
        /** The share link. */
        LINK,
    }

    /** Why an edit could not be read back, in both interface languages. */
    class Failure(val ru: String, val en: String) : Exception(en)

    /** The forms this node has, the one closest to how it is normally written first. */
    fun kinds(server: ProxyConfig): List<Kind> =
        if (server.proto == ProxyProtocol.WIREGUARD) listOf(Kind.WIREGUARD, Kind.JSON, Kind.LINK)
        else listOf(Kind.JSON, Kind.LINK)

    private val pretty = Json { prettyPrint = true }

    fun text(kind: Kind, server: ProxyConfig, engine: CoreEngine = CoreEngine.AUTO): String = when (kind) {
        Kind.LINK -> ShareLink.encode(server).orEmpty()
        Kind.JSON -> {
            val tag = server.name
            val element: JsonElement = if (engine.resolve(server) == CoreEngine.XRAY) {
                XrayConfig.outbound(server, tag)
            } else {
                SingboxConfig.outbound(server, tag)
            }
            pretty.encodeToString(JsonElement.serializer(), element)
        }
        Kind.WIREGUARD -> wireGuardConf(server)
    }

    /** `wg-quick` text, the form the peer was most likely handed over in. */
    private fun wireGuardConf(server: ProxyConfig): String = buildList {
        add("[Interface]")
        server.privateKey?.let { add("PrivateKey = $it") }
        server.localAddresses?.takeIf { it.isNotEmpty() }?.let { add("Address = ${it.joinToString(", ")}") }
        server.mtu?.let { add("MTU = $it") }
        add("")
        add("[Peer]")
        server.peerPublicKey?.let { add("PublicKey = $it") }
        server.presharedKey?.let { add("PresharedKey = $it") }
        add("Endpoint = ${server.address}:${server.port}")
        add("AllowedIPs = ${server.effectiveAllowedIPs.joinToString(", ")}")
    }.joinToString("\n")

    /**
     * Reads an edited form back into a node, keeping what the form does not
     * carry: the id above all, so groups, rules and annotations still find it.
     */
    fun parse(kind: Kind, text: String, original: ProxyConfig): ProxyConfig {
        val parsed: ProxyConfig = when (kind) {
            Kind.LINK -> try {
                LinkParser.parse(text.trim())
            } catch (e: Exception) {
                throw Failure(e.message ?: "Это не ссылка.", e.message ?: "Not a share link.")
            }
            Kind.JSON -> parseJson(text, original.name)
            Kind.WIREGUARD -> LinkParser.parseWireGuardConf(text, original.name)
                ?: throw Failure(
                    "В конфиге WireGuard нет адреса, приватного ключа или пира.",
                    "This WireGuard config is missing an address, a private key or a peer.",
                )
        }
        return parsed.copy(
            id = original.id,
            name = parsed.name.ifBlank { original.name },
            source = original.source,
        )
    }

    /** One outbound, a list of them, or a whole Xray config with `outbounds`. */
    private fun parseJson(text: String, name: String): ProxyConfig {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: throw Failure("Это не JSON.", "This is not valid JSON.")
        val config: JsonObject = when (element) {
            is JsonObject -> if ("outbounds" in element) element else wrap(JsonArray(listOf(element)), name)
            is JsonArray -> wrap(element, name)
            else -> throw notANode()
        }
        val body = SubscriptionPayload.classify(Json.encodeToString(JsonObject.serializer(), config))
        return body.nodes.firstOrNull()
            ?: throw notANode()
    }

    private fun notANode() = Failure(
        "В этом JSON нет сервера, к которому можно подключиться. Читается только outbound Xray; узел sing-box правьте через ссылку.",
        "This JSON does not describe a server Veil can connect to. Only Xray outbounds are read back; edit a sing-box node through its link.",
    )

    private fun wrap(outbounds: JsonArray, name: String): JsonObject = buildJsonObject {
        put("remarks", name)
        put("outbounds", outbounds)
    }

    /**
     * The credential this protocol cannot connect without, when it is absent,
     * as (ru, en). The cores accept such a node and fail later with a message
     * about the far end, so it is caught here first.
     */
    fun missingCredential(server: ProxyConfig): Pair<String, String>? {
        fun blank(value: String?) = value.isNullOrBlank()
        return when (server.proto) {
            ProxyProtocol.VLESS, ProxyProtocol.VMESS ->
                if (blank(server.uuid)) "У узла нет UUID." to "This node has no UUID." else null
            ProxyProtocol.TROJAN, ProxyProtocol.SHADOWSOCKS, ProxyProtocol.HYSTERIA2, ProxyProtocol.ANYTLS ->
                if (blank(server.password)) "У узла нет пароля." to "This node has no password." else null
            ProxyProtocol.TUIC -> when {
                blank(server.uuid) -> "У узла нет UUID." to "This node has no UUID."
                blank(server.password) -> "У узла нет пароля." to "This node has no password."
                else -> null
            }
            ProxyProtocol.WIREGUARD -> when {
                blank(server.privateKey) -> "У узла нет приватного ключа." to "This node has no private key."
                blank(server.peerPublicKey) -> "У узла нет публичного ключа пира." to "This node has no peer public key."
                else -> null
            }
        }
    }
}
