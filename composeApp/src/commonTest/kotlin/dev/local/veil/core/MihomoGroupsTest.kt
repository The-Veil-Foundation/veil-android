package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertIs

class MihomoGroupsTest {
    @Test
    fun subscriptionPipelineReturnsNodesAndGroups() {
        val body = assertIs<SubscriptionPayload.Body.Ok>(SubscriptionPayload.classify("""
            proxies:
            - name: Alpha
              type: vless
              server: a.example
              port: 443
              uuid: 12345678-1234-1234-1234-123456789abc
            proxy-groups:
            - name: Pick
              type: select
              proxies: [Alpha]
        """.trimIndent()))
        assertEquals(1, body.nodes.size)
        assertEquals(listOf(body.nodes.single().id), body.groups.single().memberIds)
        assertTrue(body.skipped.isEmpty())
    }

    private val nodes = listOf(
        ProxyConfig(name = "Alpha", proto = ProxyProtocol.VLESS, address = "a.example", port = 443),
        ProxyConfig(name = "Beta, EU", proto = ProxyProtocol.VLESS, address = "b.example", port = 443),
    )

    @Test
    fun importsSelectorsAndUrltest() {
        val parsed = MihomoGroups.parse("""
            proxy-groups:
              - name: Manual
                type: select
                proxies: [Alpha, "Beta, EU"]
              - name: Fast
                type: url-test
                url: https://example.com/check
                interval: 300
                tolerance: 30
                proxies:
                  - Alpha
                  - "Beta, EU"
            rules:
              - MATCH,Manual
        """.trimIndent(), nodes)
        assertTrue(parsed.skipped.isEmpty())
        assertEquals(listOf(GroupKind.SELECTOR, GroupKind.URLTEST), parsed.groups.map { it.kind })
        assertEquals(nodes.map { it.id }, parsed.groups.first().memberIds)
        assertEquals("300s", parsed.groups.last().interval)
        assertEquals(30, parsed.groups.last().tolerance)
    }

    @Test
    fun doesNotSilentlyChangeUnsupportedGroups() {
        val result = MihomoGroups.parse("""
            proxy-groups:
              - name: Mixed
                type: select
                proxies: [Alpha, DIRECT]
              - name: Cycle
                type: select
                proxies: [Cycle]
        """.trimIndent(), nodes)
        assertTrue(result.groups.isEmpty())
        assertEquals(2, result.skipped.size)
    }

    @Test
    fun refreshRemapsMembersAndKeepsUserSelection() {
        val imported = ServerGroup(name = "Panel", sourceName = "Panel", memberIds = nodes.map { it.id })
        val stored = nodes.map { it.copy(id = "stable-${it.name}", source = "subscription") }
        val old = imported.copy(id = "stable-group", name = "My group", source = "subscription", selectedId = stored.last().id)
        val manual = ServerGroup(name = "Personal")
        val merged = mergeImportedGroups(listOf(manual, old), listOf(imported), "subscription", nodes, stored)
        assertEquals(manual, merged.first())
        assertEquals("stable-group", merged.last().id)
        assertEquals("My group", merged.last().name)
        assertEquals(stored.last().id, merged.last().selectedId)
        assertEquals(stored.map { it.id }, merged.last().memberIds)
    }
}
