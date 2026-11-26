package dev.local.veil.platform

import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object EndpointResolver {
    private const val TAG = "VeilXray"

    private val cache = HashMap<String, String?>()

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

    fun apply(configJson: String): String {
        val doc = runCatching { Json.parseToJsonElement(configJson) }.getOrNull() ?: return configJson
        var changed = false

        fun walk(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> {
                val result = LinkedHashMap<String, JsonElement>()
                element.forEach { (key, value) ->
                    val replaced = when {

                        key == "address" && value is JsonPrimitive && value.isString -> {
                            val ip = resolveHost(value.content)
                            if (ip != null) changed = true
                            ip?.let { JsonPrimitive(it) } ?: value
                        }

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

    fun unresolvedHosts(configJson: String): List<String> = serverHosts(configJson).filter { host ->
        synchronized(cache) { cache[host] } == null
    }

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
