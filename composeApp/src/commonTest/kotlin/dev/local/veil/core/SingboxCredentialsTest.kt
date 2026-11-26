package dev.local.veil.core

import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class SingboxCredentialsTest {
    @Test
    fun usesProtocolUuidNotStorageId() {
        for (protocol in listOf(ProxyProtocol.VLESS, ProxyProtocol.VMESS)) {
            val node = ProxyConfig(
                id = "local-storage-id", name = "Test", proto = protocol,
                address = "example.org", port = 443,
                uuid = "12345678-1234-1234-1234-123456789abc",
            )
            val outbound = SingboxConfig.outbound(node, "test")
            assertEquals(node.uuid, outbound["uuid"]?.jsonPrimitive?.content)
        }
    }
}
