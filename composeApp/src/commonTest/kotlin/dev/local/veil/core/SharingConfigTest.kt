package dev.local.veil.core

import kotlinx.serialization.json.*
import kotlin.test.*

class SharingConfigTest {
    private val base = """{"inbounds":[{"tag":"tun-in"}],"outbounds":[{"tag":"proxy"}],"routing":{"rules":[{"outboundTag":"proxy"}]}}"""

    @Test fun retainedBridgeCanAuthenticateAfterSwitchingCore() {
        val outbound = Json.parseToJsonElement(SharingConfig.bridge("same-session"))
            .jsonObject["outbounds"]!!.jsonArray.single().jsonObject
        for (engine in listOf(CoreEngine.XRAY, CoreEngine.SING_BOX)) {
            val ingress = Json.parseToJsonElement(SharingConfig.attach(base, engine, "same-session"))
                .jsonObject["inbounds"]!!.jsonArray.last().jsonObject
            assertEquals(outbound["server"], ingress["listen"])
            val singbox = engine == CoreEngine.SING_BOX
            assertEquals(outbound["server_port"], ingress[if (singbox) "listen_port" else "port"])
            val account = if (singbox) ingress["users"]!!.jsonArray.single().jsonObject
                else ingress["settings"]!!.jsonObject["accounts"]!!.jsonArray.single().jsonObject
            assertEquals(outbound["username"], account[if (singbox) "username" else "user"])
            assertEquals(outbound["password"], account[if (singbox) "password" else "pass"])
        }
    }

    @Test fun coreIngressStaysOnLoopbackAndRequiresPassword() {
        for (engine in listOf(CoreEngine.XRAY, CoreEngine.SING_BOX)) {
            val result = Json.parseToJsonElement(SharingConfig.attach(base, engine, "session-secret")).jsonObject
            assertEquals(Json.parseToJsonElement(base).jsonObject["routing"], result["routing"])
            assertEquals(2, result["inbounds"]!!.jsonArray.size)
            val ingress = result["inbounds"]!!.jsonArray.last().jsonObject
            assertEquals("127.0.0.1", ingress["listen"]!!.jsonPrimitive.content)
            if (engine == CoreEngine.XRAY) {
                assertEquals("password", ingress["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
            } else {
                assertEquals("session-secret", ingress["users"]!!.jsonArray.single().jsonObject["password"]!!.jsonPrimitive.content)
            }
        }
        assertFailsWith<IllegalArgumentException> { SharingConfig.attach(base, CoreEngine.XRAY, "") }
    }

    @Test fun bothSharingModesHaveOnlyTheAuthenticatedCoreAsOutbound() {
        for (address in listOf(null, "192.168.43.1")) {
            val result = Json.parseToJsonElement(SharingConfig.bridge("core-secret", address, "client-secret")).jsonObject
            val out = result["outbounds"]!!.jsonArray.single().jsonObject
            assertEquals("socks", out["type"]!!.jsonPrimitive.content)
            assertEquals("127.0.0.1", out["server"]!!.jsonPrimitive.content)
            assertEquals("core-secret", out["password"]!!.jsonPrimitive.content)
            assertEquals("vpn", result["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
            val ingress = result["inbounds"]!!.jsonArray.single().jsonObject
            if (address == null) {
                assertEquals(2, ingress["address"]!!.jsonArray.size)
                assertFalse(ingress["auto_route"]!!.jsonPrimitive.boolean)
            } else {
                assertEquals(address, ingress["listen"]!!.jsonPrimitive.content)
                assertEquals("client-secret", ingress["users"]!!.jsonArray.single().jsonObject["password"]!!.jsonPrimitive.content)
            }
        }
    }

    @Test fun proxyRejectsWildcardPublicAndInvalidAddresses() {
        for (address in listOf("0.0.0.0", "::", "127.0.0.1", "8.8.8.8", "172.32.1.1", "192.168.1.999", "localhost")) {
            assertFailsWith<IllegalArgumentException>(address) { SharingConfig.bridge("secret", address, "secret") }
        }
        assertFailsWith<IllegalArgumentException> { SharingConfig.bridge("secret", "192.168.1.1", "") }
        for (address in listOf("10.0.0.1", "172.16.0.1", "172.31.255.1", "192.168.43.1")) assertTrue(SharingConfig.isPrivateIpv4(address))
    }
}
