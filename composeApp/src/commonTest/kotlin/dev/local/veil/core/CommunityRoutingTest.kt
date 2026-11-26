package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommunityRoutingTest {
    @Test
    fun disabledGeoRulesDoNotTriggerDownloads() {
        val rule = RoutingRule(enabled = false, domains = listOf("geosite:netflix"))
        assertTrue(!AppSettings(customRules = listOf(rule)).needsGeoAssets)
        assertTrue(AppSettings(customRules = listOf(rule.copy(enabled = true))).needsGeoAssets)
    }

    @Test
    fun mixedListMatchesDomainsOrAddresses() {
        val rules = communityRoutingRules("list", "List", RuleTarget.Direct,
            listOf("domain:example.com"), listOf("192.0.2.0/24"))
        assertEquals(2, rules.size)
        assertTrue(rules.all { it.target == RuleTarget.Direct })
        assertEquals(listOf("domain:example.com"), rules[0].domains)
        assertTrue(rules[0].ips.isEmpty())
        assertEquals(listOf("192.0.2.0/24"), rules[1].ips)
        assertTrue(rules[1].domains.isEmpty())
        assertTrue(rules[0].xrayRule()!!.containsKey("domain"))
        assertTrue(!rules[0].xrayRule()!!.containsKey("ip"))
        assertTrue(rules[1].xrayRule()!!.containsKey("ip"))
        assertTrue(!rules[1].xrayRule()!!.containsKey("domain"))
    }

    @Test
    fun emptyListsCannotBecomeCatchAllRules() {
        assertTrue(communityRoutingRules("empty", "", RuleTarget.Direct, emptyList(), emptyList()).isEmpty())
        assertEquals(1, communityRoutingRules("domains", "", RuleTarget.Block,
            listOf("example.com"), emptyList()).size)
    }
}
