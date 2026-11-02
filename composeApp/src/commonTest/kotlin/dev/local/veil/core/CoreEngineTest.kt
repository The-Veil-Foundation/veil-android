package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreEngineTest {

    private fun node(
        proto: ProxyProtocol,
        network: TransportNetwork = TransportNetwork.TCP,
    ) = ProxyConfig(
        name = "test",
        proto = proto,
        address = "example.com",
        port = 443,
        network = network,
    )

    @Test
    fun autoUsesXrayForXrayProtocols() {
        val server = node(ProxyProtocol.VLESS, TransportNetwork.WS)
        assertEquals(CoreEngine.XRAY, CoreEngine.AUTO.resolve(server))
        assertTrue(server.xrayCompatible)
    }

    @Test
    fun autoUsesSingboxForQuicProtocols() {
        listOf(ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC, ProxyProtocol.ANYTLS).forEach { proto ->
            val server = node(proto)
            assertEquals(CoreEngine.SING_BOX, CoreEngine.AUTO.resolve(server), proto.wire)
            assertFalse(server.xrayCompatible, proto.wire)
        }
    }

    @Test
    fun autoUsesXrayForXhttpTransport() {
        // XHTTP есть только в Xray, поэтому даже в режиме «Авто» — Xray.
        val server = node(ProxyProtocol.VLESS, TransportNetwork.XHTTP)
        assertEquals(CoreEngine.XRAY, CoreEngine.AUTO.resolve(server))
        assertTrue(server.xrayCompatible)
    }

    @Test
    fun explicitChoiceIsNotOverridden() {
        val quic = node(ProxyProtocol.HYSTERIA2)
        val vless = node(ProxyProtocol.VLESS)
        assertEquals(CoreEngine.XRAY, CoreEngine.XRAY.resolve(quic))
        assertEquals(CoreEngine.SING_BOX, CoreEngine.SING_BOX.resolve(vless))
    }

    @Test
    fun settingsPassEngineToTunnelOptions() {
        val settings = AppSettings(coreEngine = CoreEngine.SING_BOX)
        assertEquals(CoreEngine.SING_BOX, settings.toTunnelOptions().coreEngine)
        // По умолчанию — авто.
        assertEquals(CoreEngine.AUTO, AppSettings().toTunnelOptions().coreEngine)
    }
}
