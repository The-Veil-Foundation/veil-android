package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportedNodesTest {
    private val node = ProxyConfig(name = "Node", proto = ProxyProtocol.VLESS, address = "example.org", port = 443)

    @Test
    fun refreshKeepsIdentityUpdatesCredentialsAndRemovesMissingNodes() {
        val old = node.copy(id = "stable", source = "a", uuid = "old")
        val removed = node.copy(id = "removed", name = "Gone", source = "a")
        val other = node.copy(id = "other", source = "b")
        val fresh = node.copy(id = "new", uuid = "updated")
        val merged = mergeImportedNodes(listOf(old, removed, other), listOf(fresh, fresh), "a", true)
        assertEquals(listOf(other, fresh.copy(id = "stable", source = "a")), merged)
        val group = ServerGroup(name = "Pick", sourceName = "Pick", memberIds = listOf(fresh.id))
        val groups = mergeImportedGroups(emptyList(), listOf(group), "a", listOf(fresh), merged.reversed())
        assertEquals(listOf("stable"), groups.single().memberIds)
    }

    @Test
    fun identicalNodeInAnotherSubscriptionDoesNotStealOwnership() {
        val other = node.copy(id = "other", source = "b")
        val merged = mergeImportedNodes(listOf(other), listOf(node, node), "a", true)
        assertEquals(listOf(other, node.copy(source = "a")), merged)
    }

    @Test
    fun adoptsManualNodesWithoutChangingTheirIdentity() {
        val manual = node.copy(id = "manual")
        assertEquals(listOf(manual.copy(source = "a")), mergeImportedNodes(listOf(manual), listOf(node), "a", true))
    }

    @Test
    fun localImportPreservesUnrelatedGroupsAndSubscriptionRefreshRemovesOwnedGroups() {
        val local = ServerGroup(name = "Local", sourceName = "Local")
        val owned = ServerGroup(name = "Owned", sourceName = "Owned", source = "a")
        val manual = ServerGroup(name = "Manual")
        val groups = listOf(local, owned, manual)
        assertEquals(groups, mergeImportedGroups(groups, emptyList(), null, emptyList(), emptyList()))
        assertEquals(listOf(local, manual), mergeImportedGroups(groups, emptyList(), "a", emptyList(), emptyList()))
    }
}
