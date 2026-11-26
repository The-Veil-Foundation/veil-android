package dev.local.veil.core

import kotlinx.serialization.json.*

/** Session-only authenticated loopback ingress, attached only to the running VPN core. */
object SharingConfig {
    const val CORE_PORT = 10879
    const val PROXY_PORT = 10880
    const val IPV4 = "198.18.10.1/30"
    const val IPV6 = "fdfe:dcba:9876::1/64"

    fun attach(config: String, engine: CoreEngine, password: String): String {
        require(password.isNotBlank())
        val root = Json.parseToJsonElement(config).jsonObject
        val inbound = buildJsonObject {
            put("tag", "veil-sharing")
            put("listen", "127.0.0.1")
            if (engine == CoreEngine.SING_BOX) {
                put("type", "socks")
                put("listen_port", CORE_PORT)
                putJsonArray("users") { addJsonObject { put("username", "veil"); put("password", password) } }
            } else {
                put("protocol", "socks")
                put("port", CORE_PORT)
                putJsonObject("settings") {
                    put("auth", "password"); put("udp", true); put("ip", "127.0.0.1")
                    putJsonArray("accounts") { addJsonObject { put("user", "veil"); put("pass", password) } }
                }
            }
        }
        return JsonObject(root + ("inbounds" to JsonArray(root["inbounds"]!!.jsonArray + inbound))).toString()
    }

    fun isPrivateIpv4(address: String): Boolean {
        val octets = address.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }

    /** A separate libbox instance transports sharing traffic through the active core. */
    fun bridge(corePassword: String, address: String? = null, password: String = ""): String {
        require(corePassword.isNotBlank())
        if (address != null) {
            require(password.isNotBlank())
            require(isPrivateIpv4(address)) { "Proxy must bind to a private IPv4 address" }
        }
        return buildJsonObject {
            putJsonObject("log") { put("disabled", true) }
            putJsonArray("inbounds") { addJsonObject {
                if (address == null) {
                    put("type", "tun"); put("tag", "sharing-tun")
                    putJsonArray("address") { add(IPV4); add(IPV6) }
                    put("mtu", 1500); put("auto_route", false); put("stack", "gvisor")
                } else {
                    put("type", "mixed"); put("tag", "sharing-proxy")
                    put("listen", address); put("listen_port", PROXY_PORT)
                    putJsonArray("users") { addJsonObject { put("username", "veil"); put("password", password) } }
                }
            } }
            putJsonArray("outbounds") { addJsonObject {
                put("type", "socks"); put("tag", "vpn"); put("server", "127.0.0.1")
                put("server_port", CORE_PORT); put("version", "5")
                put("username", "veil"); put("password", corePassword)
            } }
            putJsonObject("route") { put("final", "vpn") }
        }.toString()
    }
}
