package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class SecretRedactorTest {
    @Test
    fun removesCredentialsAndEndpoints() {
        val input = """
            [error] failed https://panel.example/sub/my-secret?q=token
            password="two words secret", token=abc123, hwid=phone123
            Authorization: Bearer very-secret-value
            uuid=12345678-1234-1234-1234-123456789abc
            connected 192.168.1.10:443 [2001:db8::1]:443 proxy.example.org
            key ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789abcd
        """.trimIndent()
        val result = SecretRedactor.redact(input)
        for (secret in listOf("panel.example", "my-secret", "two words secret", "abc123", "phone123", "very-secret-value", "12345678-1234", "192.168.1.10", "2001:db8", "proxy.example.org", "ABCDEFGHIJKLMNOPQRSTUVWXYZ")) {
            assertFalse(result.contains(secret), secret)
        }
        assertTrue(result.contains("[error] failed"))
    }

    @Test
    fun retainsOrdinaryMessagesAndIsIdempotent() {
        val plain = "[info] VPN connected, received 1234 bytes"
        assertEquals(plain, SecretRedactor.redact(plain))
        val sanitized = SecretRedactor.redact("token=secret https://example.org/private")
        assertEquals(sanitized, SecretRedactor.redact(sanitized))
    }
}
