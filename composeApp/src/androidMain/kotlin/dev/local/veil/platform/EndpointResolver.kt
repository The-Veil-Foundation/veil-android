package dev.local.veil.platform

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Подставляет IP вместо доменных имён серверов в готовом конфиге Xray.
 *
 * Зачем: Go-резолвер внутри Android-процесса не видит netd и не умеет
 * спрашивать DNS — `dialing to tcp:node.example.com:443` висит вечно,
 * туннель поднимается, но трафика нет. Java-резолвер (`InetAddress`)
 * работает через netd штатно, поэтому имена резолвим здесь, а в конфиг
 * отдаём уже IP. SNI, Host-заголовок и REALITY serverName не трогаем:
 * они живут в отдельных полях и остаются доменом.
 */
object EndpointResolver {
    private const val TAG = "VeilXray"

    /** host → ip или null, если не разрешился. Живёт до перезапуска процесса. */
    private val cache = HashMap<String, String?>()

    /** «Уже IP» — резолвить не нужно. */
    fun isIpLiteral(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.isEmpty()) return false
        if (h.any { it.isLetter() && it !in "abcdefABCDEF" }) return false
        return runCatching { InetAddress.getByName(h) }.isSuccess && (h.contains('.') || h.contains(':'))
    }

    fun resolveHost(host: String): String? {
        if (host.isBlank() || isIpLiteral(host)) return null
        synchronized(cache) { if (cache.containsKey(host)) return cache[host] }

        val ip = runCatching {
            InetAddress.getAllByName(host)
                .filterIsInstance<Inet4Address>()
                .firstOrNull()
                ?.hostAddress
        }.onFailure { Log.w(TAG, "не удалось разрешить $host: ${it.message}") }
            .getOrNull()

        synchronized(cache) { cache[host] = ip }
        if (ip != null) Log.i(TAG, "endpoint $host -> $ip")
        return ip
    }

    /** Заменяет все доменные адреса серверов на IP. Возвращает тот же JSON. */
    fun apply(configJson: String): String {
        val doc = runCatching { Json.parseToJsonElement(configJson) }.getOrNull() ?: return configJson
        var changed = false

        fun walk(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> {
                val result = LinkedHashMap<String, JsonElement>()
                element.forEach { (key, value) ->
                    val replaced = when {
                        // vnext[].address, servers[].address — адрес узла.
                        key == "address" && value is JsonPrimitive && value.isString -> {
                            val ip = resolveHost(value.content)
                            if (ip != null) changed = true
                            ip?.let { JsonPrimitive(it) } ?: value
                        }
                        // WireGuard: peers[].endpoint = "host:port" / "[v6]:port".
                        key == "endpoint" && value is JsonPrimitive && value.isString -> {
                            resolveEndpointString(value.content)?.let { changed = true; JsonPrimitive(it) } ?: value
                        }
                        else -> walk(value)
                    }
                    result[key] = replaced
                }
                JsonObject(result)
            }
            is JsonArray -> JsonArray(element.map { walk(it) })
            else -> element
        }

        val out = walk(doc)
        return if (changed) Json.encodeToString(JsonElement.serializer(), out) else configJson
    }

    private fun resolveEndpointString(endpoint: String): String? {
        val host = when {
            endpoint.startsWith("[") -> endpoint.substringAfter('[').substringBefore(']')
            endpoint.count { it == ':' } == 1 -> endpoint.substringBefore(':')
            else -> return null
        }
        val ip = resolveHost(host) ?: return null
        return endpoint.replace(host, ip)
    }

    /** Домены серверов, которые не удалось разрешить (для сообщения пользователю). */
    fun unresolvedHosts(configJson: String): List<String> = serverHosts(configJson).filter { host ->
        synchronized(cache) { cache[host] } == null
    }

    /** Все доменные адреса серверов в конфиге. */
    fun serverHosts(configJson: String): List<String> {
        val doc = runCatching { Json.parseToJsonElement(configJson) as? JsonObject }.getOrNull() ?: return emptyList()
        val outbounds = doc["outbounds"] as? JsonArray ?: return emptyList()
        val hosts = mutableListOf<String>()
        outbounds.forEach { outbound ->
            val settings = (outbound as? JsonObject)?.get("settings") as? JsonObject ?: return@forEach
            listOf("vnext", "servers").forEach { key ->
                (settings[key] as? JsonArray)?.forEach { entry ->
                    val address = (entry as? JsonObject)?.get("address") as? JsonPrimitive ?: return@forEach
                    if (!address.isString) return@forEach
                    if (!isIpLiteral(address.content)) hosts += address.content
                }
            }
        }
        return hosts
    }
}
