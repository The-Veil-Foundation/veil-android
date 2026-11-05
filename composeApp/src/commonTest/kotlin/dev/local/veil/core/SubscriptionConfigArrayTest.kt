package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Подписка-JSON: массив готовых конфигов Xray, по одному на страну, с
 * несколькими транспортами под балансировщиком. Значения вымышленные.
 */
class SubscriptionConfigArrayTest {

    private val body = """
    [
      {
        "remarks": "Тест · Эстония",
        "observatory": {
          "subjectSelector": ["A-REALITY", "A-TLS"],
          "probeUrl": "https://www.gstatic.com/generate_204",
          "probeInterval": "30s"
        },
        "routing": {
          "domainStrategy": "IPIfNonMatch",
          "rules": [
            { "type": "field", "ip": ["geoip:private"], "outboundTag": "direct" },
            { "type": "field", "domain": ["geosite:private"], "outboundTag": "direct" },
            { "type": "field", "domain": ["geosite:category-ads-all"], "outboundTag": "block" },
            { "type": "field", "network": "tcp,udp", "balancerTag": "auto" }
          ],
          "balancers": [
            { "tag": "auto", "selector": ["A-REALITY", "A-TLS"], "strategy": { "type": "leastLoad" } }
          ]
        },
        "outbounds": [
          {
            "tag": "A-REALITY", "protocol": "vless",
            "settings": { "vnext": [ { "address": "192.0.2.10", "port": 2443,
              "users": [ { "id": "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b", "encryption": "none", "flow": "xtls-rprx-vision" } ] } ] },
            "streamSettings": { "network": "tcp", "security": "reality",
              "realitySettings": { "serverName": "example.com", "fingerprint": "firefox",
                "publicKey": "J8a3kL9mN2pQ4rS6tU8vW0xY2zA4bC6dE8fG0hI2jK0", "shortId": "ab12cd34" } }
          },
          {
            "tag": "A-TLS", "protocol": "vless",
            "settings": { "vnext": [ { "address": "192.0.2.10", "port": 1443,
              "users": [ { "id": "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b", "encryption": "none" } ] } ] },
            "streamSettings": { "network": "tcp", "security": "tls",
              "tlsSettings": { "serverName": "example.org", "fingerprint": "firefox" } }
          },
          { "tag": "direct", "protocol": "freedom" },
          { "tag": "block", "protocol": "blackhole" }
        ]
      }
    ]
    """.trimIndent()

    private fun node(): ProxyConfig {
        val result = SubscriptionPayload.classify(body)
        assertIs<SubscriptionPayload.Body.Ok>(result)
        assertEquals(SubscriptionPayload.Format.XRAY_CONFIGS, result.format)
        assertEquals(1, result.nodes.size)
        return result.nodes.single()
    }

    @Test
    fun `config array becomes one balanced node`() {
        val node = node()
        assertEquals("Тест · Эстония", node.name)
        assertEquals(2, node.balanced)
    }

    @Test
    fun `geo matchers are stripped because the app ships no geo database`() {
        val text = XrayConfig.json(node(), TunnelOptions(geoAvailable = false))
        assertFalse("geoip:" in text, "geoip: оставил бы ядро без запуска")
        assertFalse("geosite:" in text, "geosite: оставил бы ядро без запуска")
        // private заменён явными диапазонами, а не потерян.
        assertTrue("10.0.0.0/8" in text)
        assertTrue("domain:local" in text)
        // Правило с одними geosite-тегами не должно превратиться в «всё подряд».
        assertFalse("category-ads" in text)
        val rules = Json.parseToJsonElement(text).jsonObject["routing"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals(3, rules.size)
        // Балансировщик и observatory остаются; слишком частые пробы подписки
        // замедляются, чтобы не будить радио каждые 30 секунд.
        assertTrue("balancerTag" in text)
        assertTrue("leastLoad" in text)
    }

    @Test
    fun `geo matchers are kept when the database is present`() {
        val text = XrayConfig.json(node(), TunnelOptions(geoAvailable = true))
        assertTrue("geoip:private" in text)
        assertTrue("geosite:category-ads-all" in text)
    }

    @Test
    fun `inbounds are replaced by the tunnel`() {
        val text = XrayConfig.json(node(), TunnelOptions())
        assertTrue("\"protocol\":\"tun\"" in text)
    }

    @Test
    fun `provider balancer gets a fallback tag`() {
        val config = Json.parseToJsonElement(XrayConfig.json(node(), TunnelOptions())).jsonObject
        val balancer = config["routing"]!!.jsonObject["balancers"]!!.jsonArray.single().jsonObject
        // Первый выход под селектором: до первого замера observatory и при
        // упавших пробах leastLoad иначе возвращает пустой тег.
        assertEquals("A-REALITY", balancer["fallbackTag"]?.jsonPrimitive?.content)
    }

    @Test
    fun `provider probes are slowed down for battery`() {
        val config = Json.parseToJsonElement(XrayConfig.json(node(), TunnelOptions())).jsonObject
        val observatory = config["observatory"]!!.jsonObject
        // 30s из подписки: каждая проба — HTTPS-запрос через узел.
        assertEquals("3m", observatory["probeInterval"]!!.jsonPrimitive.content)
        assertEquals(2, observatory["subjectSelector"]!!.jsonArray.size)
    }

    @Test
    fun `provider burst probes are slowed down for battery`() {
        val withBurst = body.replace(
            "\"remarks\": \"Тест · Эстония\",",
            "\"burstObservatory\": { \"subjectSelector\": [\"A-REALITY\"], " +
                "\"pingConfig\": { \"destination\": \"https://www.gstatic.com/generate_204\", " +
                "\"interval\": \"10s\", \"sampling\": 3, \"timeout\": \"5s\" } },\n        " +
                "\"remarks\": \"Тест · Эстония\",",
        )
        val parsed = SubscriptionPayload.classify(withBurst)
        assertIs<SubscriptionPayload.Body.Ok>(parsed)
        val config = Json.parseToJsonElement(
            XrayConfig.json(parsed.nodes.single(), TunnelOptions()),
        ).jsonObject
        val ping = config["burstObservatory"]!!.jsonObject["pingConfig"]!!.jsonObject
        // 10s × 3 = 30s между пачками — слишком часто; 180s / 3 = 60s.
        assertEquals("60s", ping["interval"]!!.jsonPrimitive.content)
    }

    @Test
    fun `provider policy keeps its levels and gets outbound counters`() {
        val withPolicy = body.replace(
            "\"remarks\": \"Тест · Эстония\",",
            "\"policy\": { \"levels\": { \"0\": { \"connIdle\": 300 } }, " +
                "\"system\": { \"statsInboundUplink\": true } },\n        " +
                "\"remarks\": \"Тест · Эстония\",",
        )
        val parsed = SubscriptionPayload.classify(withPolicy)
        assertIs<SubscriptionPayload.Body.Ok>(parsed)
        val config = Json.parseToJsonElement(
            XrayConfig.json(parsed.nodes.single(), TunnelOptions()),
        ).jsonObject
        val policy = config["policy"]!!.jsonObject
        assertEquals(
            "300",
            policy["levels"]!!.jsonObject["0"]!!.jsonObject["connIdle"]!!.jsonPrimitive.content,
        )
        val system = policy["system"]!!.jsonObject
        assertTrue(system["statsInboundUplink"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(system["statsOutboundUplink"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(system["statsOutboundDownlink"]!!.jsonPrimitive.content.toBoolean())
    }
}
