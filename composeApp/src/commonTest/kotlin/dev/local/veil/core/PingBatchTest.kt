package dev.local.veil.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PingBatchTest {

    private fun node(i: Int, proto: ProxyProtocol = ProxyProtocol.VLESS) = ProxyConfig(
        id = "n$i",
        name = "node $i",
        proto = proto,
        address = "198.51.100.$i",
        port = 443,
        uuid = "11111111-1111-1111-1111-11111111111$i",
        password = "secret$i",
    )

    private val options = AppSettings(fragment = true, dnsSettings = DnsSettings(enabled = true)).toTunnelOptions().forProbe()

    @Test
    fun xrayRoutesEachInboundToItsNode() {
        val nodes = (1..3).map { node(it) }
        val doc = PingBatch.xray(nodes, listOf(21001, 21002, 21003), "u", "p", options)
        val inbounds = doc["inbounds"] as JsonArray
        val outbounds = (doc["outbounds"] as JsonArray).map { (it as JsonObject)["tag"].toString().trim('"') }
        assertEquals(3, inbounds.size)
        assertTrue(outbounds.containsAll(listOf("ping-out-0", "ping-out-1", "ping-out-2")))
        assertTrue("proxy" !in outbounds)
        val rules = ((doc["routing"] as JsonObject)["rules"] as JsonArray)
        assertEquals(3, rules.size)
        println("XRAYCFG " + doc)
    }

    @Test
    fun singboxRoutesEachInboundToItsNode() {
        val nodes = (1..3).map { node(it, ProxyProtocol.HYSTERIA2) }
        val doc = PingBatch.singbox(nodes, listOf(21001, 21002, 21003), "u", "p", options)
        val outbounds = (doc["outbounds"] as JsonArray).map { (it as JsonObject)["tag"].toString().trim('"') }
        assertTrue(outbounds.containsAll(listOf("ping-out-0", "ping-out-1", "ping-out-2", "direct")))
        assertTrue("proxy" !in outbounds)
        // Каждый detour должен вести в существующий выход: иначе ядро не стартует.
        val detours = Regex("\"detour\":\"([^\"]+)\"").findAll(doc.toString()).map { it.groupValues[1] }.toList()
        assertTrue(detours.all { it in outbounds }, "висячие detour: $detours")
        println("SBCFG " + doc)
    }
}
