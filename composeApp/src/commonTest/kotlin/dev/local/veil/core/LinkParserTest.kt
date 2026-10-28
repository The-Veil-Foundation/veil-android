package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinkParserTest {

    private val reality =
        "vless://0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b@nl1.example.com:443" +
            "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=J8a3kL9mN2pQ4rS6tU8vW0xY2zA4bC6dE8fG0hI2jK&sid=ab12cd34" +
            "&type=tcp&flow=xtls-rprx-vision#Amsterdam"

    @Test
    fun parsesVlessReality() {
        val cfg = LinkParser.parse(reality)

        assertEquals(ProxyProtocol.VLESS, cfg.proto)
        assertEquals("nl1.example.com", cfg.address)
        assertEquals(443, cfg.port)
        assertEquals("0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b", cfg.uuid)
        assertEquals(StreamSecurity.REALITY, cfg.security)
        assertEquals("www.microsoft.com", cfg.sni)
        assertEquals("J8a3kL9mN2pQ4rS6tU8vW0xY2zA4bC6dE8fG0hI2jK", cfg.publicKey)
        assertEquals("ab12cd34", cfg.shortId)
        assertEquals("xtls-rprx-vision", cfg.flow)
        assertEquals("Amsterdam", cfg.name)
        assertTrue(cfg.isUsable)
    }

    @Test
    fun trojanDefaultsToTls() {
        val cfg = LinkParser.parse("trojan://pw@fi.example.com:443#FI")
        assertEquals(ProxyProtocol.TROJAN, cfg.proto)
        assertEquals(StreamSecurity.TLS, cfg.security)
        assertEquals("pw", cfg.password)
    }

    @Test
    fun parsesShadowsocksSip002() {
        val cfg = LinkParser.parse("ss://YWVzLTI1Ni1nY206cGFzc3dvcmQxMjM=@se.example.com:8388#Stockholm")
        assertEquals(ProxyProtocol.SHADOWSOCKS, cfg.proto)
        assertEquals("aes-256-gcm", cfg.method)
        assertEquals("password123", cfg.password)
        assertEquals(8388, cfg.port)
    }

    @Test
    fun parsesWebSocketTransport() {
        val cfg = LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@de.example.com:8443" +
                "?security=tls&sni=cdn.example.org&type=ws&host=cdn.example.org&path=%2Fws#DE",
        )
        assertEquals(TransportNetwork.WS, cfg.network)
        assertEquals("/ws", cfg.path)
        assertEquals("cdn.example.org", cfg.host)
        assertEquals(StreamSecurity.TLS, cfg.security)
    }

    @Test
    fun parsesVmessBase64() {
        val b64 = "eyJ2IjogIjIiLCAicHMiOiAi0KjQstC10LnRhtCw0YDQuNGPIiwgImFkZCI6ICJjaC5leGFtcGxlLmNvbSIsICJwb3J0IjogIjQ0MyIsICJpZCI6ICI5OTk5OTk5OS04ODg4LTc3NzctNjY2Ni01NTU1NTU1NTU1NTUiLCAiYWlkIjogIjAiLCAic2N5IjogImF1dG8iLCAibmV0IjogIndzIiwgInR5cGUiOiAibm9uZSIsICJob3N0IjogImNoLmV4YW1wbGUuY29tIiwgInBhdGgiOiAiL3ZtIiwgInRscyI6ICJ0bHMiLCAic25pIjogImNoLmV4YW1wbGUuY29tIiwgImZwIjogImNocm9tZSJ9"
        val cfg = LinkParser.parse("vmess://$b64")

        assertEquals(ProxyProtocol.VMESS, cfg.proto)
        assertEquals("ch.example.com", cfg.address)
        assertEquals(TransportNetwork.WS, cfg.network)
        assertEquals(StreamSecurity.TLS, cfg.security)
        assertEquals("/vm", cfg.path)
    }

    @Test
    fun marksHysteria2UnsupportedForXray() {
        val cfg = LinkParser.parse(
            "hysteria2://letmein@jp.example.com:443?sni=jp.example.com&obfs=salamander#Tokyo",
        )
        assertEquals(ProxyProtocol.HYSTERIA2, cfg.proto)
        assertFalse(cfg.isUsable)
    }

    @Test
    fun parsesWireGuardConf() {
        val conf = """
            [Interface]
            PrivateKey = aGVsbG8=
            Address = 10.0.0.2/32
            MTU = 1408

            [Peer]
            PublicKey = d29ybGQ=
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = wg.example.com:51820
        """.trimIndent()

        val cfg = assertNotNull(LinkParser.parseWireGuardConf(conf))
        assertEquals("wg.example.com", cfg.address)
        assertEquals(51820, cfg.port)
        assertEquals(1408, cfg.mtu)
        assertEquals(listOf("10.0.0.2/32"), cfg.localAddresses)
        assertTrue(cfg.carriesEverything)
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<LinkParseException> { LinkParser.parse("просто текст") }
        assertFailsWith<LinkParseException> { LinkParser.parse("ftp://example.com:21") }
    }

    @Test
    fun skipsBrokenLinesInList() {
        val many = LinkParser.parseMany(listOf(reality, "мусор", "trojan://pw@fi.example.com:443#FI").joinToString("\n"))
        assertEquals(2, many.size)
    }
}

class XrayConfigTest {

    private val reality =
        "vless://0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b@nl1.example.com:443" +
            "?security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=J8a3kL9mN2pQ4rS6tU8vW0xY2zA4bC6dE8fG0hI2jK&sid=ab12cd34" +
            "&type=tcp&flow=xtls-rprx-vision#NL"

    @Test
    fun emitsTunInbound() {
        val cfg = XrayConfig.build(LinkParser.parse(reality))
        val inbound = cfg["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", inbound["protocol"]!!.jsonPrimitive.content)
        assertEquals("tun-in", inbound["tag"]!!.jsonPrimitive.content)
        assertEquals("veil0", inbound["settings"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun emitsRealityStreamSettings() {
        val cfg = XrayConfig.build(LinkParser.parse(reality))
        val outbound = cfg["outbounds"]!!.jsonArray[0].jsonObject
        val stream = outbound["streamSettings"]!!.jsonObject

        assertEquals("reality", stream["security"]!!.jsonPrimitive.content)
        val realityBlock = stream["realitySettings"]!!.jsonObject
        assertEquals("www.microsoft.com", realityBlock["serverName"]!!.jsonPrimitive.content)
        assertEquals("chrome", realityBlock["fingerprint"]!!.jsonPrimitive.content)
        assertEquals("ab12cd34", realityBlock["shortId"]!!.jsonPrimitive.content)

        val user = outbound["settings"]!!.jsonObject["vnext"]!!.jsonArray[0]
            .jsonObject["users"]!!.jsonArray[0].jsonObject
        assertEquals("xtls-rprx-vision", user["flow"]!!.jsonPrimitive.content)
        assertEquals("none", user["encryption"]!!.jsonPrimitive.content)
    }

    @Test
    fun emitsDnsRuleAndOutbound() {
        val cfg = XrayConfig.build(LinkParser.parse(reality))
        val tags = cfg["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("dns-out" in tags, "есть dns-out: $tags")

        val rules = cfg["routing"]!!.jsonObject["rules"]!!.jsonArray
        val dnsRule = rules.map { it.jsonObject }.first { it["port"] != null }
        assertEquals("dns-out", dnsRule["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun emitsStatsPolicy() {
        val cfg = XrayConfig.build(LinkParser.parse(reality))
        assertNotNull(cfg["stats"])
        val system = cfg["policy"]!!.jsonObject["system"]!!.jsonObject
        assertTrue(system["statsOutboundUplink"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun outputIsValidJson() {
        val text = XrayConfig.json(LinkParser.parse(reality))
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertNotNull(parsed["inbounds"])
    }

    @Test
    fun buildsForEveryXrayProtocol() {
        val links = listOf(
            reality,
            "trojan://pw@fi.example.com:443#FI",
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQxMjM=@se.example.com:8388#SE",
            "vmess://eyJhZGQiOiAiY2guZXhhbXBsZS5jb20iLCAicG9ydCI6ICI0NDMiLCAiaWQiOiAiOTk5OTk5OTktODg4OC03Nzc3LTY2NjYtNTU1NTU1NTU1NTU1IiwgIm5ldCI6ICJ3cyIsICJ0bHMiOiAidGxzIiwgInBhdGgiOiAiL3ZtIiwgInBzIjogIkNIIiwgImhvc3QiOiAiY2guZXhhbXBsZS5jb20ifQ==",
            "wireguard://cHJpdmF0ZQ==@wg.example.com:51820?public_key=cHVibGlj&address=10.0.0.2/32#WG",
        )
        for (link in links) {
            val cfg = LinkParser.parse(link)
            val out = XrayConfig.build(cfg)["outbounds"]!!.jsonArray[0].jsonObject
            assertEquals("proxy", out["tag"]!!.jsonPrimitive.content)
            assertNotNull(out["protocol"])
        }
    }

    @Test
    fun hysteria2IsNotBuildable() {
        val cfg = LinkParser.parse("hysteria2://pw@jp.example.com:443#JP")
        // Конфиг собирается, но как freedom — подключать нельзя.
        assertEquals(ProxyProtocol.HYSTERIA2, cfg.proto)
        assertFalse(cfg.isUsable)
    }
}

class SubscriptionPayloadTest {

    @Test
    fun acceptsSeveralLinks() {
        val input = listOf(
            "vless://11111111-2222-3333-4444-555555555555@a.example.com:443?security=tls#A",
            "trojan://pw@b.example.com:443#B",
        ).joinToString("\n")

        val body = SubscriptionPayload.classify(input)
        assertTrue(body is SubscriptionPayload.Body.Ok)
        assertEquals(2, body.nodes.size)
        assertEquals(SubscriptionPayload.Format.LINKS, body.format)
    }

    @Test
    fun acceptsBase64Body() {
        val encoded = base64Encode("trojan://pw@b.example.com:443#B")
        val body = SubscriptionPayload.classify(encoded)
        assertTrue(body is SubscriptionPayload.Body.Ok)
        assertEquals(1, body.nodes.size)
        assertEquals(SubscriptionPayload.Format.BASE64_LINKS, body.format)
    }

    @Test
    fun acceptsWireGuardConf() {
        val conf = """
            [Interface]
            PrivateKey = aGVsbG8=
            Address = 10.0.0.2/32

            [Peer]
            PublicKey = d29ybGQ=
            Endpoint = wg.example.com:51820
        """.trimIndent()

        val body = SubscriptionPayload.classify(conf)
        assertTrue(body is SubscriptionPayload.Body.Ok)
        assertEquals(ProxyProtocol.WIREGUARD, body.nodes[0].proto)
    }

    @Test
    fun rejectsMihomoYaml() {
        val yaml = """
            proxies:
              - name: A
                type: vless
                server: a.example.com
        """.trimIndent()
        val body = SubscriptionPayload.classify(yaml)
        assertTrue(body is SubscriptionPayload.Body.Failure)
        assertEquals(SubscriptionPayload.Format.MIHOMO, body.format)
    }

    private val arrayBody = """
        [{
          "remarks": "🇩🇪 Германия №1",
          "dns": {"servers": ["1.1.1.1"], "queryStrategy": "UseIP"},
          "routing": {"rules": [{"type": "field", "domain": ["domain:ntv.ru"], "outboundTag": "direct"}]},
          "inbounds": [{"protocol": "socks", "port": 10808}, {"protocol": "http", "port": 10809}],
          "outbounds": [
            {"tag": "proxy", "protocol": "vless",
             "settings": {"vnext": [{"address": "a.example.com", "port": 443,
               "users": [{"id": "11111111-2222-3333-4444-555555555555", "encryption": "none",
                          "flow": "xtls-rprx-vision"}]}]},
             "streamSettings": {"network": "tcp", "security": "reality",
               "realitySettings": {"serverName": "www.microsoft.com", "publicKey": "pk",
                                   "shortId": "ab", "fingerprint": "chrome"}}},
            {"tag": "direct", "protocol": "freedom"},
            {"tag": "block", "protocol": "blackhole"}
          ]
        }]
    """.trimIndent()

    @Test
    fun parsesArrayOfXrayConfigs() {
        val body = SubscriptionPayload.classify(arrayBody)
        assertTrue(body is SubscriptionPayload.Body.Ok)
        assertEquals(SubscriptionPayload.Format.XRAY_CONFIGS, body.format)
        assertEquals(1, body.nodes.size)

        val node = body.nodes[0]
        assertEquals("🇩🇪 Германия №1", node.name)
        assertEquals(ProxyProtocol.VLESS, node.proto)
        assertEquals("a.example.com", node.address)
        assertEquals(443, node.port)
        assertEquals(StreamSecurity.REALITY, node.security)
        assertEquals("xtls-rprx-vision", node.flow)
        assertNotNull(node.rawConfigJson)
    }

    @Test
    fun keepsPanelRoutingButReplacesInbounds() {
        val body = SubscriptionPayload.classify(arrayBody) as SubscriptionPayload.Body.Ok
        val node = body.nodes[0]

        val config = XrayConfig.build(node)
        val inbound = config["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals("tun", inbound["protocol"]!!.jsonPrimitive.content)

        // Маршрут панели (обход ntv.ru) должен остаться.
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rules.any { it["domain"]?.toString()?.contains("ntv.ru") == true }, "правило панели сохранено")

        val tags = config["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("proxy" in tags)
    }

    @Test
    fun fragmentGoesIntoTcpHeaderMask() {
        val node = LinkParser.parse(
            "vless://11111111-2222-3333-4444-555555555555@a.example.com:443" +
                "?security=reality&sni=www.microsoft.com&pbk=pk&sid=ab&type=tcp#DE",
        )
        val config = XrayConfig.build(node, dev.local.veil.core.TunnelOptions(fragment = true))
        val stream = config["outbounds"]!!.jsonArray[0].jsonObject["streamSettings"]!!.jsonObject
        val mask = stream["finalmask"]!!.jsonObject["tcp"]!!.jsonArray[0].jsonObject

        assertEquals("fragment", mask["type"]!!.jsonPrimitive.content)
        val settings = mask["settings"]!!.jsonObject
        assertEquals("tlshello", settings["packets"]!!.jsonPrimitive.content)
        assertEquals("100-200", settings["length"]!!.jsonPrimitive.content)
    }

    @Test
    fun bypassRuRoutesRussianDomainsDirect() {
        val node = LinkParser.parse("trojan://pw@a.example.com:443#DE")
        val config = XrayConfig.build(node, dev.local.veil.core.TunnelOptions(bypassRu = true))
        val rules = config["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

        val ruRule = rules.firstOrNull { rule ->
            rule["domain"]?.toString()?.contains("domain:ru") == true
        }
        assertNotNull(ruRule, "правило для .ru отсутствует")
        assertEquals("direct", ruRule["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun marksHysteriaNodesUnusable() {
        val hysteria = """
            [{"remarks": "US · Hysteria2",
              "inbounds": [{"protocol": "socks", "port": 10808}],
              "outbounds": [
                {"tag": "proxy", "protocol": "hysteria",
                 "settings": {"version": 2, "address": "h.example.com", "port": 443}},
                {"tag": "direct", "protocol": "freedom"}
              ]}]
        """.trimIndent()

        val body = SubscriptionPayload.classify(hysteria) as SubscriptionPayload.Body.Ok
        assertEquals(ProxyProtocol.HYSTERIA2, body.nodes[0].proto)
        assertFalse(body.nodes[0].isUsable)
    }
}

private fun base64Encode(text: String): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val bytes = text.encodeToByteArray()
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        out.append(table[b0 shr 2])
        out.append(table[((b0 and 0x03) shl 4) or (b1 shr 4)])
        out.append(if (i + 1 < bytes.size) table[((b1 and 0x0F) shl 2) or (b2 shr 6)] else '=')
        out.append(if (i + 2 < bytes.size) table[b2 and 0x3F] else '=')
        i += 3
    }
    return out.toString()
}
