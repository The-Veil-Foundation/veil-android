package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object NodeRepresentation {

    enum class Kind {

        WIREGUARD,

        JSON,

        LINK,
    }

    class Failure(val ru: String, val en: String) : Exception(en)

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
