package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ссылка → узел → ссылка → узел: ничего не теряется. Значения вымышленные. */
class ShareLinkTest {

    private fun roundTrip(cfg: ProxyConfig): ProxyConfig {
        val link = assertNotNull(ShareLink.encode(cfg))
        return LinkParser.parse(link)
    }

    @Test
    fun `vless reality survives a round trip`() {
        val original = LinkParser.parse(
            "vless://0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b@nl1.example.com:443" +
                "?encryption=none&security=reality&sni=www.microsoft.com&fp=chrome" +
                "&pbk=J8a3kL9mN2pQ4rS6tU8vW0xY2zA4bC6dE8fG0hI2jK&sid=ab12cd34" +
                "&type=tcp&flow=xtls-rprx-vision#Amsterdam %231",
        )
        val back = roundTrip(original)
        assertEquals(original.address, back.address)
        assertEquals(original.port, back.port)
        assertEquals(original.uuid, back.uuid)
        assertEquals(original.flow, back.flow)
        assertEquals(original.security, back.security)
        assertEquals(original.sni, back.sni)
        assertEquals(original.fingerprint, back.fingerprint)
        assertEquals(original.publicKey, back.publicKey)
        assertEquals(original.shortId, back.shortId)
        assertEquals(original.network, back.network)
        assertEquals(original.name, back.name)
    }

    @Test
    fun `trojan ws tls survives a round trip`() {
        val original = ProxyConfig(
            name = "Тест / Trojan",
            proto = ProxyProtocol.TROJAN,
            address = "t.example.com",
            port = 8443,
            password = "p@ss:word/with?odd#chars",
            network = TransportNetwork.WS,
            security = StreamSecurity.TLS,
            sni = "t.example.com",
            path = "/ws path",
            host = "cdn.example.com",
        )
        val back = roundTrip(original)
        assertEquals(original.password, back.password)
        assertEquals(original.path, back.path)
        assertEquals(original.host, back.host)
        assertEquals(original.name, back.name)
        assertEquals(StreamSecurity.TLS, back.security)
    }

    @Test
    fun `vmess survives a round trip`() {
        val original = ProxyConfig(
            name = "VMess",
            proto = ProxyProtocol.VMESS,
            address = "vm.example.com",
            port = 443,
            uuid = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b",
            alterId = 0,
            network = TransportNetwork.WS,
            security = StreamSecurity.TLS,
            sni = "vm.example.com",
            path = "/v",
            host = "vm.example.com",
        )
        val back = roundTrip(original)
        assertEquals(original.uuid, back.uuid)
        assertEquals(original.address, back.address)
        assertEquals(original.path, back.path)
        assertEquals(StreamSecurity.TLS, back.security)
    }

    @Test
    fun `shadowsocks survives a round trip`() {
        val original = ProxyConfig(
            name = "SS",
            proto = ProxyProtocol.SHADOWSOCKS,
            address = "ss.example.com",
            port = 8388,
            method = "chacha20-ietf-poly1305",
            password = "secret:with:colons",
        )
        val back = roundTrip(original)
        assertEquals(original.method, back.method)
        assertEquals(original.password, back.password)
        assertEquals(original.port, back.port)
    }

    @Test
    fun `ipv6 address is bracketed`() {
        val original = ProxyConfig(
            name = "v6",
            proto = ProxyProtocol.VLESS,
            address = "2001:db8::1",
            port = 443,
            uuid = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b",
        )
        val link = assertNotNull(ShareLink.encode(original))
        assertTrue("[2001:db8::1]:443" in link)
    }

    @Test
    fun `raw config nodes have no share link`() {
        val node = ProxyConfig(
            name = "raw",
            proto = ProxyProtocol.VLESS,
            address = "x.example.com",
            port = 443,
            uuid = "0f1e2d3c-4b5a-6978-8a9b-0c1d2e3f4a5b",
            rawConfigJson = "{}",
        )
        assertNull(ShareLink.encode(node))
    }

    @Test
    fun `pairing accepts only local network addresses`() {
        val token = "0123456789abcdef0123456789abcdef"
        assertNotNull(Pairing.parse("http://192.168.1.20:41234/pair/$token"))
        assertNotNull(Pairing.parse("http://10.0.0.5:8080/pair/$token"))
        assertNotNull(Pairing.parse("http://172.20.1.2:8080/pair/$token"))
        assertNull(Pairing.parse("http://8.8.8.8:8080/pair/$token"))
        assertNull(Pairing.parse("http://example.com:8080/pair/$token"))
        assertNull(Pairing.parse("https://192.168.1.20:8080/pair/$token"))
        assertNull(Pairing.parse("http://172.40.1.2:8080/pair/$token"))
        assertNull(Pairing.parse("http://192.168.1.20:8080/pair/short"))
    }

    @Test
    fun `pasted text is split into subscriptions and servers`() {
        val payload = Pairing.fromText(
            "https://sub.example.com/abc\nvless://u@h.example.com:443#x\n\nмусор\nhttps://sub.example.com/abc",
        )
        assertEquals(listOf("https://sub.example.com/abc"), payload.subscriptions)
        assertEquals(1, payload.servers.size)
    }
}
