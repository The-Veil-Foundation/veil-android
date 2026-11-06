package dev.local.veil.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Конфиг пробного ядра на пачку узлов — порт ProxyProbe из veil-macos.
 *
 * Раньше реальный пинг поднимал ядро на каждый узел и мерил по очереди: сотня
 * узлов шла минутами. Здесь одно ядро держит по локальному SOCKS на узел,
 * каждый вход ведёт в свой выход, и запросы идут параллельно.
 *
 * Общие части (DNS, служебные выходы вроде фрагментации) берутся из обычного
 * пробного конфига первого узла, чтобы замер шёл тем же путём, что и туннель.
 */
object PingBatch {

    /** Узлов на одно ядро: 500 входов в одном процессе стартовали бы секундами. */
    const val CHUNK = 32

    fun inboundTag(index: Int) = "ping-in-$index"
    fun outboundTag(index: Int) = "ping-out-$index"

    /** Выходы, которые обычный конфиг строит под выбранный узел — в пачке они лишние. */
    private fun isNodeTag(tag: String?): Boolean =
        tag == null || tag == ProfileTags.DEFAULT_SELECTOR ||
            tag.startsWith("srv-") || tag.startsWith("grp-") || tag.startsWith("bal-")

    private fun JsonObject.tag(): String? = (this["tag"] as? JsonPrimitive)?.contentOrNull

    /** Узел можно мерить пачкой: готовый конфиг из подписки несёт свою топологию. */
    fun batchable(node: ProxyConfig): Boolean = node.rawConfigJson == null

    fun xray(
        nodes: List<ProxyConfig>,
        ports: List<Int>,
        user: String,
        pass: String,
        options: TunnelOptions,
    ): JsonObject {
        require(nodes.isNotEmpty() && nodes.size == ports.size)
        val base = XrayConfig.build(
            nodes.first(),
            options,
            XrayInbound.LocalProxy(socks = ports.first(), http = ports.first() + 1, user = user, pass = pass),
        )
        val service = (base["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filterNot { isNodeTag(it.tag()) }
        val strategy = ((base["routing"] as? JsonObject)?.get("domainStrategy") as? JsonPrimitive)?.contentOrNull ?: "AsIs"

        val doc = base.toMutableMap()
        doc.remove("observatory")
        doc.remove("burstObservatory")
        // Адреса узлов уже разрешены, имя тестового сайта разрешает сам узел:
        // свой DNS пробному ядру не нужен, а с ним запросы ушли бы в первый узел.
        doc.remove("dns")
        doc["inbounds"] = buildJsonArray {
            ports.forEachIndexed { index, port ->
                add(
                    buildJsonObject {
                        put("tag", inboundTag(index))
                        put("listen", "127.0.0.1")
                        put("port", port)
                        put("protocol", "socks")
                        putJsonObject("settings") {
                            put("udp", false)
                            put("auth", "password")
                            putJsonArray("accounts") {
                                add(buildJsonObject { put("user", user); put("pass", pass) })
                            }
                        }
                    },
                )
            }
        }
        doc["outbounds"] = buildJsonArray {
            // Первый выход — выход по умолчанию: пусть это будет служебный, а не узел.
            service.forEach { add(it) }
            nodes.forEachIndexed { index, node -> add(XrayConfig.outbound(node, outboundTag(index), options)) }
        }
        doc["routing"] = buildJsonObject {
            put("domainStrategy", strategy)
            putJsonArray("rules") {
                nodes.indices.forEach { index ->
                    add(
                        buildJsonObject {
                            put("type", "field")
                            putJsonArray("inboundTag") { add(JsonPrimitive(inboundTag(index))) }
                            put("outboundTag", outboundTag(index))
                        },
                    )
                }
            }
        }
        return JsonObject(doc)
    }

    fun singbox(
        nodes: List<ProxyConfig>,
        ports: List<Int>,
        user: String,
        pass: String,
        options: TunnelOptions,
    ): JsonObject {
        require(nodes.isNotEmpty() && nodes.size == ports.size)
        val base = SingboxConfig.build(
            nodes.first(),
            options,
            SingboxConfig.Inbound.LocalProxy(socks = ports.first(), user = user, pass = pass),
        )
        val service = (base["outbounds"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filterNot { isNodeTag(it.tag()) }

        val doc = base.toMutableMap()
        doc.remove("experimental")
        // DNS обычного конфига ходит через выход «proxy», которого в пачке нет.
        // Как на macOS: системный резолвер, имя сайта всё равно разрешает узел.
        doc["dns"] = buildJsonObject {
            putJsonArray("servers") {
                add(buildJsonObject { put("type", "local"); put("tag", "local") })
            }
        }
        doc["inbounds"] = buildJsonArray {
            ports.forEachIndexed { index, port ->
                add(
                    buildJsonObject {
                        put("type", "socks")
                        put("tag", inboundTag(index))
                        put("listen", "127.0.0.1")
                        put("listen_port", port)
                        putJsonArray("users") {
                            add(buildJsonObject { put("username", user); put("password", pass) })
                        }
                    },
                )
            }
        }
        doc["outbounds"] = buildJsonArray {
            nodes.forEachIndexed { index, node -> add(SingboxConfig.outbound(node, outboundTag(index), options.fragment)) }
            service.forEach { add(it) }
        }
        doc["route"] = buildJsonObject {
            put("default_domain_resolver", "local")
            putJsonArray("rules") {
                nodes.indices.forEach { index ->
                    add(
                        buildJsonObject {
                            putJsonArray("inbound") { add(JsonPrimitive(inboundTag(index))) }
                            put("outbound", outboundTag(index))
                        },
                    )
                }
            }
            put("final", ProfileTags.DIRECT)
        }
        return JsonObject(doc)
    }

    private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray(emptyList())
    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
