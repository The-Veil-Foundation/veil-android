package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NodeRepresentationTest {

    private val vless = ProxyConfig(
        id = "keep-me",
        name = "Amsterdam",
        proto = ProxyProtocol.VLESS,
        address = "198.51.100.7",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        security = StreamSecurity.REALITY,
        sni = "example.com",
        publicKey = "pk",
        source = "https://panel.example/sub",
    )

    private val wireguard = ProxyConfig(
        id = "wg-id",
        name = "Home",
        proto = ProxyProtocol.WIREGUARD,
        address = "203.0.113.4",
        port = 51820,
        privateKey = "cHJpdmF0ZWtleXByaXZhdGVrZXlwcml2YXRla2V5MDA=",
        peerPublicKey = "cHVibGlja2V5cHVibGlja2V5cHVibGlja2V5cHViMDA=",
        localAddresses = listOf("10.0.0.2/32"),
    )

    @Test
    fun linkRoundTripKeepsIdentity() {
        val text = NodeRepresentation.text(NodeRepresentation.Kind.LINK, vless)
        val back = NodeRepresentation.parse(NodeRepresentation.Kind.LINK, text, vless)
        assertEquals("keep-me", back.id)
        assertEquals(vless.source, back.source)
        assertEquals(vless.uuid, back.uuid)
        assertEquals(StreamSecurity.REALITY, back.security)
    }

    @Test
    fun xrayJsonRoundTrip() {
        val text = NodeRepresentation.text(NodeRepresentation.Kind.JSON, vless)
        val back = NodeRepresentation.parse(NodeRepresentation.Kind.JSON, text, vless)
        assertEquals("keep-me", back.id)
        assertEquals("Amsterdam", back.name)
        assertEquals(vless.address, back.address)
        assertEquals(vless.uuid, back.uuid)
    }

    @Test
    fun wireGuardConfRoundTrip() {
        assertEquals(NodeRepresentation.Kind.WIREGUARD, NodeRepresentation.kinds(wireguard).first())
        val text = NodeRepresentation.text(NodeRepresentation.Kind.WIREGUARD, wireguard)
        val back = NodeRepresentation.parse(NodeRepresentation.Kind.WIREGUARD, text, wireguard)
        assertEquals("wg-id", back.id)
        assertEquals(wireguard.peerPublicKey, back.peerPublicKey)
        assertEquals(wireguard.port, back.port)
    }

    @Test
    fun garbageIsRefusedWithAReason() {
        assertFailsWith<NodeRepresentation.Failure> {
            NodeRepresentation.parse(NodeRepresentation.Kind.JSON, "{not json", vless)
        }
        assertFailsWith<NodeRepresentation.Failure> {
            NodeRepresentation.parse(NodeRepresentation.Kind.WIREGUARD, "[Interface]", wireguard)
        }
    }

    @Test
    fun missingCredentialIsNamed() {
        assertNull(NodeRepresentation.missingCredential(vless))
        assertNotNull(NodeRepresentation.missingCredential(vless.copy(uuid = " ")))
        assertNotNull(NodeRepresentation.missingCredential(wireguard.copy(privateKey = null)))
    }
}
