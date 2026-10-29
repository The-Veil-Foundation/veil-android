package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Разбор тела подписки. Порт `SubscriptionPayload.swift`.
 *
 * Панели отдают разное, и форматы нельзя путать: список ссылок теряет
 * маршруты и DNS, а полный конфиг их несёт. Поэтому тело сначала
 * классифицируется, а не прогоняется через парсер ссылок.
 */
object SubscriptionPayload {

    enum class Format(val label: String) {
        LINKS("Share links"),
        BASE64_LINKS("Share links (base64)"),
        XRAY_CONFIGS("Xray config"),
        WIREGUARD_CONF("WireGuard profile"),
        MIHOMO("Mihomo YAML"),
        UNKNOWN("Unrecognised"),
    }

    sealed interface Body {
        val format: Format
        val nodes: List<ProxyConfig>
        val skipped: List<String>

        data class Ok(
            override val format: Format,
            override val nodes: List<ProxyConfig>,
            override val skipped: List<String> = emptyList(),
        ) : Body

        data class Failure(
            override val format: Format,
            val message: String,
            override val nodes: List<ProxyConfig> = emptyList(),
            override val skipped: List<String> = emptyList(),
        ) : Body
    }

    fun classify(input: String): Body {
        val text = input.trim()
        if (text.isEmpty()) return Body.Failure(Format.UNKNOWN, "Пустой ответ подписки")

        // 1. JSON-массив полных конфигов (Remnawave/Happ) или один конфиг.
        val first = text.first()
        if (first == '[' || first == '{') {
            jsonBody(text)?.let { return it }
        }

        // 2. Mihomo / Clash YAML: импортируем узлы из `proxies`.
        if (text.contains("proxies:") && text.contains("type:")) {
            MihomoParser.parse(text)?.let { return it }
        }

        // 3. wg-quick
        if (text.contains("[Interface]", true) && text.contains("[Peer]", true)) {
            val cfg = LinkParser.parseWireGuardConf(text)
                ?: return Body.Failure(Format.WIREGUARD_CONF, "Не удалось разобрать WireGuard-профиль")
            return Body.Ok(Format.WIREGUARD_CONF, listOf(cfg))
        }

        // 4. Ссылки или base64 от ссылок.
        parseLinks(text)?.let { return it }

        val decoded = runCatching { base64Decode(text) }.getOrNull()
        if (decoded != null && decoded != text) {
            parseLinks(decoded)?.let { return it.copy(format = Format.BASE64_LINKS) }
        }

        return Body.Failure(Format.UNKNOWN, "Формат подписки не распознан")
    }

    /* ---------------- JSON ---------------- */

    private fun jsonBody(text: String): Body? {
        val element = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null

        val configs: List<JsonObject> = when (element) {
            is JsonArray -> element.mapNotNull { it as? JsonObject }
            is JsonObject -> if (element.containsKey("outbounds")) listOf(element) else return null
            else -> return null
        }

        if (configs.isEmpty()) return null

        val nodes = ArrayList<ProxyConfig>()
        val skipped = ArrayList<String>()

        for (config in configs) {
            val node = configToProfile(config)
            if (node == null) {
                skipped.add(config["remarks"]?.jsonPrimitive?.contentOrNull ?: "config")
            } else {
                nodes.add(node)
            }
        }

        if (nodes.isEmpty()) {
            return Body.Failure(Format.XRAY_CONFIGS, "В подписке нет ни одной ноды, которую можно запустить")
        }
        return Body.Ok(Format.XRAY_CONFIGS, nodes, skipped)
    }

    /** Один элемент массива → профиль + сохранённый документ. */
    private fun configToProfile(config: JsonObject): ProxyConfig? {
        val outbounds = (config["outbounds"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?: return null

        val proxy = outbounds.firstOrNull {
            val p = it["protocol"]?.jsonPrimitive?.contentOrNull
            p != null && p !in setOf("freedom", "blackhole", "dns")
        } ?: return null

        val protocol = proxy["protocol"]?.jsonPrimitive?.contentOrNull ?: return null
        val settings = proxy["settings"] as? JsonObject
        val stream = proxy["streamSettings"] as? JsonObject

        val (address, port) = endpoint(settings) ?: return null

        val proto = when (protocol.lowercase()) {
            "vless" -> ProxyProtocol.VLESS
            "vmess" -> ProxyProtocol.VMESS
            "trojan" -> ProxyProtocol.TROJAN
            "shadowsocks" -> ProxyProtocol.SHADOWSOCKS
            "wireguard" -> ProxyProtocol.WIREGUARD
            "hysteria", "hysteria2" -> ProxyProtocol.HYSTERIA2
            "tuic" -> ProxyProtocol.TUIC
            else -> return null
        }

        val user = firstUser(settings)
        val network = stream?.get("network")?.jsonPrimitive?.contentOrNull
        val security = stream?.get("security")?.jsonPrimitive?.contentOrNull
        val reality = stream?.get("realitySettings") as? JsonObject
        val tls = stream?.get("tlsSettings") as? JsonObject

        return ProxyConfig(
            name = config["remarks"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                .ifEmpty { "$address:$port" },
            proto = proto,
            address = address,
            port = port,
            uuid = user?.get("id")?.jsonPrimitive?.contentOrNull,
            password = settings?.get("servers")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("password")?.jsonPrimitive?.contentOrNull,
            method = settings?.get("servers")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("method")?.jsonPrimitive?.contentOrNull,
            flow = user?.get("flow")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            encryption = user?.get("encryption")?.jsonPrimitive?.contentOrNull,
            alterId = user?.get("alterId")?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            network = TransportNetwork.fromWire(network),
            security = StreamSecurity.fromWire(security),
            sni = (reality ?: tls)?.get("serverName")?.jsonPrimitive?.contentOrNull,
            fingerprint = (reality ?: tls)?.get("fingerprint")?.jsonPrimitive?.contentOrNull,
            publicKey = reality?.get("publicKey")?.jsonPrimitive?.contentOrNull,
            shortId = reality?.get("shortId")?.jsonPrimitive?.contentOrNull,
            rawConfigJson = Json.encodeToString(JsonObject.serializer(), config),
        )
    }

    private fun endpoint(settings: JsonObject?): Pair<String, Int>? {
        settings ?: return null

        (settings["vnext"] as? JsonArray)?.firstOrNull()?.let { v ->
            val o = v as? JsonObject ?: return@let
            val address = o["address"]?.jsonPrimitive?.contentOrNull ?: return@let
            val port = o["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@let
            return address to port
        }

        (settings["servers"] as? JsonArray)?.firstOrNull()?.let { s ->
            val o = s as? JsonObject ?: return@let
            val address = o["address"]?.jsonPrimitive?.contentOrNull ?: return@let
            val port = o["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@let
            return address to port
        }

        // hysteria/tuic кладут адрес прямо в settings
        settings["address"]?.jsonPrimitive?.contentOrNull?.let { address ->
            settings["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.let { port ->
                return address to port
            }
        }

        // wireguard: endpoint в peer'е
        (settings["peers"] as? JsonArray)?.firstOrNull()?.let { p ->
            val o = p as? JsonObject ?: return@let
            val ep = o["endpoint"]?.jsonPrimitive?.contentOrNull ?: return@let
            val idx = ep.lastIndexOf(':')
            if (idx <= 0) return@let
            val host = ep.substring(0, idx).removePrefix("[").removeSuffix("]")
            val port = ep.substring(idx + 1).toIntOrNull() ?: return@let
            return host to port
        }

        return null
    }

    private fun firstUser(settings: JsonObject?): JsonObject? {
        settings ?: return null
        (settings["vnext"] as? JsonArray)?.firstOrNull()?.let { v ->
            return (v as? JsonObject)?.get("users")?.jsonArray?.firstOrNull() as? JsonObject
        }
        return null
    }

    /* ---------------- ссылки ---------------- */

    private fun parseLinks(text: String): Body.Ok? {
        var skipped = 0
        val nodes = ArrayList<ProxyConfig>()

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            if (!trimmed.contains("://")) continue
            runCatching { LinkParser.parse(trimmed) }
                .onSuccess { nodes.add(it) }
                .onFailure { skipped++ }
        }

        if (nodes.isEmpty()) return null
        return Body.Ok(Format.LINKS, nodes, if (skipped > 0) listOf("не разобрано ссылок: $skipped") else emptyList())
    }
}

/** Ключ дедупликации: одна и та же нода из подписки не должна дублироваться. */
fun ProxyConfig.dedupKey(): String = "$address:$port:$name"
