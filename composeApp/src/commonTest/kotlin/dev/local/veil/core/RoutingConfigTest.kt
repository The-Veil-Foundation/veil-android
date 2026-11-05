package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoutingConfigTest {

    private val node = ProxyConfig(
        name = "node",
        proto = ProxyProtocol.VLESS,
        address = "198.51.100.7",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
    )

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.outbounds(): JsonArray =
        this["outbounds"]!! as JsonArray

    @Test
    fun xrayRendersUserRuleBeforeCatchAll() {
        val rule = RoutingRule(
            name = "direct example",
            target = RuleTarget(TargetKind.DIRECT),
            domains = listOf("domain:example.com"),
        )
        val config = json(XrayConfig.json(node, TunnelOptions(rules = listOf(rule))))
        val rules = (config["routing"]!!.jsonObject["rules"]!! as JsonArray)
            .map { it.jsonObject }

        val direct = rules.firstOrNull { it["outboundTag"]?.jsonPrimitive?.content == "direct" }
            ?: error("нет правила direct")
        assertEquals("domain:example.com", (direct["domain"] as JsonArray)[0].jsonPrimitive.content)
        // Правило идёт раньше финального «всё в proxy».
        val last = rules.last()
        assertEquals("proxy", last["outboundTag"]?.jsonPrimitive?.content)
    }

    @Test
    fun xrayBuildsBalancerForFastestGroup() {
        val member = node.copy(id = "m1", name = "m1")
        val member2 = node.copy(id = "m2", name = "m2", port = 8443)
        val config = json(
            XrayConfig.json(
                node,
                TunnelOptions(balancerMembers = listOf(member, member2)),
            ),
        )
        val routing = config["routing"]!!.jsonObject
        val balancers = routing["balancers"] as JsonArray
        val balancer = balancers[0].jsonObject
        assertEquals("proxy", balancer["tag"]!!.jsonPrimitive.content)
        assertEquals(2, (balancer["selector"] as JsonArray).size)
        assertEquals("bal-0", balancer["fallbackTag"]!!.jsonPrimitive.content)
        assertTrue(config.containsKey("observatory"))
        val lastRule = (routing["rules"] as JsonArray).last().jsonObject
        assertEquals("proxy", lastRule["balancerTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun singboxRendersRuleAndDns() {
        val rule = RoutingRule(
            target = RuleTarget(TargetKind.BLOCK),
            domains = listOf("domain:ads.example"),
        )
        val dns = DnsSettings(
            servers = listOf(
                DnsServerEntry(tag = "dns-remote", kind = DnsServerKind.HTTPS, server = "1.1.1.1"),
                DnsServerEntry(tag = "dns-bootstrap", kind = DnsServerKind.UDP, server = "1.1.1.1"),
            ),
        )
        val config = json(
            SingboxConfig.build(
                node,
                TunnelOptions(rules = listOf(rule), dnsSettings = dns),
            ).toString(),
        )
        val routeRules = (config["route"]!!.jsonObject["rules"] as JsonArray).map { it.jsonObject }
        assertTrue(routeRules.any { it["outbound"]?.jsonPrimitive?.content == "block" })
        val servers = (config["dns"]!!.jsonObject["servers"] as JsonArray).map { it.jsonObject }
        assertTrue(servers.any { it["type"]?.jsonPrimitive?.content == "https" })
    }

    @Test
    fun presetRulesAreIncluded() {
        val settings = AppSettings(routingPreset = RoutingPreset.BYPASS_RU)
        val options = settings.toTunnelOptions()
        assertTrue(options.rules.any { "domain:ru" in it.domains })
    }
}
