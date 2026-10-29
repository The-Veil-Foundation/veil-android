package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MihomoParserTest {

    private val sample = """
        port: 7890
        mode: rule
        proxies:
          - name: "DE 1"
            type: vless
            server: de.example.com
            port: 443
            uuid: 11111111-2222-3333-4444-555555555555
            network: ws
            tls: true
            servername: de.example.com
            client-fingerprint: chrome
            ws-opts:
              path: /ws
              headers:
                Host: de.example.com
          - name: "Reality 1"
            type: vless
            server: rt.example.com
            port: 8443
            uuid: 99999999-2222-3333-4444-555555555555
            flow: xtls-rprx-vision
            tls: true
            reality-opts:
              public-key: PUBKEY
              short-id: abcd
          - name: "SS 1"
            type: ss
            server: 1.2.3.4
            port: 8388
            cipher: aes-256-gcm
            password: secret
          - name: "Hysteria"
            type: hysteria2
            server: hy.example.com
            port: 443
            password: x
        proxy-groups:
          - name: auto
            type: url-test
            proxies: [DE 1, SS 1]
    """.trimIndent()

    @Test
    fun parsesClashProxies() {
        val body = SubscriptionPayload.classify(sample)
        assertTrue(body is SubscriptionPayload.Body.Ok, "ожидался успешный разбор")
        body as SubscriptionPayload.Body.Ok

        assertEquals(SubscriptionPayload.Format.MIHOMO, body.format)
        assertEquals(3, body.nodes.size, "hysteria2 должна быть пропущена")
        assertEquals(listOf("Hysteria"), body.skipped)

        val vless = body.nodes.first { it.name == "DE 1" }
        assertEquals(ProxyProtocol.VLESS, vless.proto)
        assertEquals("de.example.com", vless.address)
        assertEquals(443, vless.port)
        assertEquals(TransportNetwork.WS, vless.network)
        assertEquals(StreamSecurity.TLS, vless.security)
        assertEquals("de.example.com", vless.sni)
        assertEquals("/ws", vless.path)
        assertEquals("de.example.com", vless.host)
        assertEquals("chrome", vless.fingerprint)

        val reality = body.nodes.first { it.name == "Reality 1" }
        assertEquals(StreamSecurity.REALITY, reality.security)
        assertEquals("PUBKEY", reality.publicKey)
        assertEquals("abcd", reality.shortId)
        assertEquals("xtls-rprx-vision", reality.flow)

        val ss = body.nodes.first { it.name == "SS 1" }
        assertEquals(ProxyProtocol.SHADOWSOCKS, ss.proto)
        assertEquals("aes-256-gcm", ss.method)
        assertEquals("secret", ss.password)
    }

    @Test
    fun returnsNullWhenNoProxiesSection() {
        assertEquals(null, MihomoParser.parse("rules:\n  - MATCH,DIRECT\n"))
    }

    @Test
    fun reportsFailureWhenNothingSupported() {
        val body = MihomoParser.parse(
            """
            proxies:
              - name: hy
                type: hysteria2
                server: a.example.com
                port: 443
                password: x
            """.trimIndent(),
        )
        assertNotNull(body)
        assertTrue(body is SubscriptionPayload.Body.Failure)
        assertEquals(SubscriptionPayload.Format.MIHOMO, body.format)
    }
}
