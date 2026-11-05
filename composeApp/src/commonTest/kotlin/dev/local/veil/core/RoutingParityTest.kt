package dev.local.veil.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Порт RoutingRuleOrderTests.swift и маршрутной части SingBoxProfileTests.swift
 * из veil-macos: порядок правил, MatcherSyntax и рендер в оба ядра.
 */
class RoutingParityTest {

    private val node = ProxyConfig(
        id = "active",
        name = "node",
        proto = ProxyProtocol.VLESS,
        address = "198.51.100.7",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
    )

    private fun settings(preset: RoutingPreset, blockAds: Boolean = false) = AppSettings(
        routingPreset = preset,
        blockAds = blockAds,
        customRules = listOf(
            RoutingRule(name = "Work app", target = RuleTarget.Direct, processNames = listOf("com.slack")),
        ),
    )

    private fun List<RoutingRule>.indexOfName(name: String) = indexOfFirst { it.name == name }

    /* ---------------- порядок ---------------- */

    @Test
    fun userRulesApplyUnderEveryPreset() {
        for (preset in RoutingPreset.entries) {
            val rules = settings(preset).effectiveRoutingRules()
            assertTrue(rules.any { it.name == "Work app" }, "$preset dropped the user's rule")
        }
    }

    @Test
    fun lanBypassStaysAheadOfUserRules() {
        val rules = settings(RoutingPreset.BYPASS_LAN).effectiveRoutingRules()
        assertTrue(rules.indexOfName("LAN direct") in 0 until rules.indexOfName("Work app"))
    }

    @Test
    fun userRulesOutrankThePresetsCountryRules() {
        val rules = settings(RoutingPreset.BYPASS_CHINA).effectiveRoutingRules()
        assertTrue(rules.indexOfName("Work app") < rules.indexOfName("China sites direct"))
    }

    @Test
    fun adBlockingComesFirst() {
        val rules = settings(RoutingPreset.BYPASS_RU, blockAds = true).effectiveRoutingRules()
        assertEquals(RuleTarget.Block, rules.first().target)
        assertEquals(listOf("geosite:category-ads-all"), rules.first().domains)
    }

    @Test
    fun anAddressedRuleOutranksTheLanBypass() {
        val s = AppSettings(
            routingPreset = RoutingPreset.BYPASS_LAN,
            customRules = listOf(
                RoutingRule(name = "Office host", target = RuleTarget.server("peer"), ips = listOf("172.16.4.10/32")),
            ),
        )
        val rules = s.effectiveRoutingRules()
        assertTrue(rules.indexOfName("Office host") < rules.indexOfName("LAN direct"))
    }

    @Test
    fun anApplicationRuleStaysBehindTheLanBypass() {
        val s = AppSettings(
            routingPreset = RoutingPreset.BYPASS_LAN,
            customRules = listOf(
                RoutingRule(name = "Addressed", target = RuleTarget.Direct, domains = listOf("example.com")),
                RoutingRule(name = "Work app", target = RuleTarget.Direct, processNames = listOf("com.slack")),
            ),
        )
        val rules = s.effectiveRoutingRules()
        assertTrue(rules.indexOfName("Addressed") < rules.indexOfName("LAN direct"))
        assertTrue(rules.indexOfName("LAN direct") < rules.indexOfName("Work app"))
    }

    @Test
    fun aSourceRuleDoesNotJumpTheBypass() {
        val s = AppSettings(
            routingPreset = RoutingPreset.BYPASS_LAN,
            customRules = listOf(
                RoutingRule(
                    name = "From the NAS",
                    target = RuleTarget.Direct,
                    ips = listOf("192.168.1.9/32"),
                    direction = RuleDirection.SOURCE,
                ),
            ),
        )
        val rules = s.effectiveRoutingRules()
        assertTrue(rules.indexOfName("LAN direct") < rules.indexOfName("From the NAS"))
    }

    @Test
    fun communityListsSitBetweenUserAndPresetRules() {
        val community = RoutingRule(name = "YouTube", target = RuleTarget.Proxy, domains = listOf("youtube.com"))
        val rules = settings(RoutingPreset.BYPASS_RU).effectiveRoutingRules(listOf(community))
        assertTrue(rules.indexOfName("Work app") < rules.indexOfName("YouTube"))
        assertTrue(rules.indexOfName("YouTube") < rules.indexOfName("RU gov & category direct"))
    }

    @Test
    fun builtInRulesAreStillGuardsThenPreset() {
        for (preset in RoutingPreset.entries) {
            val combined = preset.guardRules(blockAds = true) + preset.presetRules()
            assertEquals(combined.map { it.name }, preset.builtInRules(blockAds = true).map { it.name })
        }
    }

    @Test
    fun everyRoutingEditChangesTheFingerprint() {
        val base = AppSettings()
        val edits = listOf(
            "a rule" to base.copy(customRules = listOf(RoutingRule(name = "Office", ips = listOf("172.16.4.10/32")))),
            "the preset" to base.copy(routingPreset = RoutingPreset.BYPASS_RU),
            "ad blocking" to base.copy(blockAds = !base.blockAds),
            "a group" to base.copy(groups = listOf(ServerGroup(name = "EU", memberIds = listOf("x")))),
            "a community list" to base.copy(communityLists = listOf("youtube")),
            "the resolver" to base.copy(dnsSettings = base.dnsSettings.copy(enabled = !base.dnsSettings.enabled)),
        )
        for ((what, edited) in edits) {
            assertNotEquals(base.routingFingerprint, edited.routingFingerprint, "editing $what left the fingerprint alone")
        }
        assertEquals(base.routingFingerprint, base.copy(appearance = Appearance.DARK).routingFingerprint)
    }

    @Test
    fun legacyBypassRuMigratesToThePreset() {
        val migrated = AppSettings(bypassRu = true).migrated()
        assertEquals(RoutingPreset.BYPASS_RU, migrated.routingPreset)
        assertFalse(migrated.bypassRu)
    }

    @Test
    fun onlyRussiaInsideListRemains() {
        assertEquals(listOf("ru-inside"), CommunityCatalog.all.map { it.id })
    }

    @Test
    fun legacyCommunityTargetMigratesToDirectOnce() {
        val migrated = AppSettings(communityListTarget = RuleTarget.Proxy).migrated()
        assertEquals(RuleTarget.Direct, migrated.communityListTarget)
        assertEquals(2, migrated.settingsVersion)
        // После миграции явный выбор прокси больше не перезаписывается.
        assertEquals(
            RuleTarget.Proxy,
            migrated.copy(communityListTarget = RuleTarget.Proxy).migrated().communityListTarget,
        )
    }

    @Test
    fun oldStoredSettingsStillDecode() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val stored = """{"routingPreset":"BYPASS_RU","bypassRu":true,
            "customRules":[{"id":"r1","target":{"kind":"SERVER","id":"n1"},"domains":["a.com"]}],
            "communityListTarget":{"kind":"DIRECT"}}"""
        val decoded = json.decodeFromString(AppSettings.serializer(), stored)
        assertEquals(RuleTarget.server("n1"), decoded.customRules.single().target)
        assertEquals(RoutingPreset.BYPASS_RU, decoded.routingPreset)
    }

    /* ---------------- MatcherSyntax ---------------- */

    @Test
    fun portRangesAcceptBothSeparatorsAndOpenEnds() {
        val (ports, ranges) = MatcherSyntax.ports("1000-2000, 3000:4000, :1024, 50000:")
        assertEquals(emptyList(), ports)
        assertEquals(listOf("1000:2000", "3000:4000", ":1024", "50000:"), ranges)
    }

    @Test
    fun invalidPortEntriesAreDropped() {
        val (ports, ranges) = MatcherSyntax.ports("0, 443, 70000, http, 2000-1000, 10-99999, -, :, 5-x, 8080")
        assertEquals(listOf(443, 8080), ports)
        assertEquals(emptyList(), ranges)
    }

    @Test
    fun xrayPortsUseDashes() {
        assertEquals("443,1000-2000,1-1024", MatcherSyntax.xrayPorts("443, 1000:2000, :1024"))
    }

    /* ---------------- sing-box ---------------- */

    private fun route(rule: RoutingRule): JsonObject? = SingboxConfig.routeRule(rule)

    private fun JsonObject.strings(key: String) = this[key]?.jsonArray?.map { it.jsonPrimitive.content }

    @Test
    fun appRuleRendersPackageName() {
        val rendered = route(RoutingRule(target = RuleTarget.Direct, processNames = listOf("org.telegram.messenger")))
        assertEquals(listOf("org.telegram.messenger"), rendered?.strings("package_name"))
        assertEquals("direct", rendered?.get("outbound")?.jsonPrimitive?.content)
    }

    @Test
    fun geositeBecomesARuleSetInsteadOfBeingDropped() {
        val rule = RoutingRule(target = RuleTarget.Direct, domains = listOf("geosite:cn"))
        assertEquals(listOf("geosite-cn"), route(rule)?.strings("rule_set"))
        val sets = RuleSetCatalog.derive(listOf(rule))
        assertEquals(1, sets.size)
        assertEquals("geosite-cn", sets[0].tag)
        assertTrue(sets[0].url.endsWith("geosite-cn.srs"))
    }

    @Test
    fun geoipPrivateUsesTheNativeMatcher() {
        val rule = RoutingRule(target = RuleTarget.Direct, ips = listOf("geoip:private"))
        assertEquals(true, route(rule)?.get("ip_is_private")?.jsonPrimitive?.boolean)
        assertTrue(RuleSetCatalog.derive(listOf(rule)).isEmpty())
    }

    @Test
    fun bareDomainMatchesTheApexAndItsSubdomains() {
        val rendered = route(RoutingRule(domains = listOf("example.com")))
        assertEquals(listOf("example.com"), rendered?.strings("domain"))
        assertEquals(listOf(".example.com"), rendered?.strings("domain_suffix"))
    }

    @Test
    fun portsAndRangesAreSplit() {
        val rendered = route(RoutingRule(target = RuleTarget.Block, port = "443, 1000-2000, 80"))
        assertEquals(listOf(443, 80), rendered?.get("port")?.jsonArray?.map { it.jsonPrimitive.int })
        assertEquals(listOf("1000:2000"), rendered?.strings("port_range"))
    }

    @Test
    fun ruleWithNoMatcherIsDropped() {
        assertNull(route(RoutingRule(target = RuleTarget.Block)))
        assertNull(route(RoutingRule(domains = listOf("ext:foo.dat:bar"))))
    }

    @Test
    fun singboxProfileSniffsHijacksDnsAndDeclaresRuleSets() {
        val options = AppSettings(routingPreset = RoutingPreset.BYPASS_RU).toTunnelOptions()
        val config = Json.parseToJsonElement(SingboxConfig.build(node, options).toString()).jsonObject
        val route = config["route"]!!.jsonObject
        val rules = (route["rules"] as JsonArray).map { it.jsonObject }
        assertEquals("sniff", rules[0]["action"]?.jsonPrimitive?.content)
        assertTrue(rules.any { it["action"]?.jsonPrimitive?.content == "hijack-dns" })
        assertEquals("proxy", route["final"]?.jsonPrimitive?.content)
        val tags = (route["rule_set"] as JsonArray).map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("geosite-category-ru" in tags && "geoip-ru" in tags)
        // Устаревших для 1.12 полей нет.
        val outbounds = (config["outbounds"] as JsonArray).map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertFalse("dns" in outbounds)
        val tun = (config["inbounds"] as JsonArray)[0].jsonObject
        assertNull(tun["inet4_address"])
        assertNotNull(tun["address"])
    }

    @Test
    fun ruleNamingAServerBuildsThatServer() {
        val other = node.copy(id = "de", name = "DE", proto = ProxyProtocol.HYSTERIA2, password = "p")
        val rule = RoutingRule(name = "via DE", target = RuleTarget.server("de"), domains = listOf("example.org"))
        val options = AppSettings(customRules = listOf(rule)).toTunnelOptions(servers = listOf(node, other))
        val config = Json.parseToJsonElement(SingboxConfig.build(node, options).toString()).jsonObject
        val outTags = (config["outbounds"] as JsonArray).map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("srv-de" in outTags)
        val rendered = (config["route"]!!.jsonObject["rules"] as JsonArray).map { it.jsonObject }
            .first { it.strings("domain") == listOf("example.org") }
        assertEquals("srv-de", rendered["outbound"]?.jsonPrimitive?.content)
    }

    @Test
    fun missingTargetFallsBackToProxy() {
        val rule = RoutingRule(target = RuleTarget.group("gone"), domains = listOf("example.org"))
        val options = AppSettings(customRules = listOf(rule)).toTunnelOptions(servers = listOf(node))
        val config = Json.parseToJsonElement(SingboxConfig.build(node, options).toString()).jsonObject
        val rendered = (config["route"]!!.jsonObject["rules"] as JsonArray).map { it.jsonObject }
            .first { it.strings("domain") == listOf("example.org") }
        assertEquals("proxy", rendered["outbound"]?.jsonPrimitive?.content)
    }

    @Test
    fun groupBecomesSelectorOverItsMembers() {
        val a = node.copy(id = "a", name = "A")
        val b = node.copy(id = "b", name = "B")
        val group = ServerGroup(id = "eu", name = "EU", memberIds = listOf("a", "b"), selectedId = "b")
        val rule = RoutingRule(target = RuleTarget.group("eu"), processNames = listOf("com.app"))
        val options = AppSettings(customRules = listOf(rule), groups = listOf(group))
            .toTunnelOptions(servers = listOf(node, a, b))
        val config = Json.parseToJsonElement(SingboxConfig.build(node, options).toString()).jsonObject
        val selector = (config["outbounds"] as JsonArray).map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "grp-eu" }
        assertEquals("selector", selector["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("srv-a", "srv-b"), selector.strings("outbounds"))
        assertEquals("srv-b", selector["default"]?.jsonPrimitive?.content)
    }

    /* ---------------- Xray ---------------- */

    private fun xrayRules(options: TunnelOptions): List<JsonObject> {
        val config = XrayConfig.build(node, options)
        return (config["routing"]!!.jsonObject["rules"] as JsonArray).map { it.jsonObject }
    }

    @Test
    fun appRuleIsNotEmittedForXray() {
        assertNull(RoutingRule(target = RuleTarget.Direct, processNames = listOf("org.telegram.messenger")).xrayRule())
    }

    @Test
    fun xrayWithoutGeoKeepsNationalZonesOfBypassRussia() {
        val options = AppSettings(routingPreset = RoutingPreset.BYPASS_RU).toTunnelOptions(geoAvailable = false)
        val rules = xrayRules(options)
        val text = rules.toString()
        assertFalse("geosite:" in text || "geoip:ru" in text, "geo-матчеры без базы уронят Xray")
        val ru = rules.first { it.strings("domain")?.contains("domain:ru") == true }
        assertEquals("direct", ru["outboundTag"]?.jsonPrimitive?.content)
    }

    @Test
    fun xrayWithGeoKeepsCategories() {
        val options = AppSettings(routingPreset = RoutingPreset.BYPASS_RU).toTunnelOptions(geoAvailable = true)
        assertTrue("geosite:category-ru" in xrayRules(options).toString())
    }

    @Test
    fun xrayRoutesANamedServerThroughItsOwnOutbound() {
        val other = node.copy(id = "nl", name = "NL", port = 8443)
        val rule = RoutingRule(target = RuleTarget.server("nl"), domains = listOf("domain:example.org"))
        val options = AppSettings(customRules = listOf(rule)).toTunnelOptions(servers = listOf(node, other))
        val config = XrayConfig.build(node, options)
        val tags = (config["outbounds"] as JsonArray).map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("srv-nl" in tags)
        val rendered = (config["routing"]!!.jsonObject["rules"] as JsonArray).map { it.jsonObject }
            .first { it.strings("domain") == listOf("domain:example.org") }
        assertEquals("srv-nl", rendered["outboundTag"]?.jsonPrimitive?.content)
    }

    @Test
    fun xrayFastestGroupBecomesABalancer() {
        val a = node.copy(id = "a", name = "A")
        val b = node.copy(id = "b", name = "B", port = 8443)
        val group = ServerGroup(id = "fast", kind = GroupKind.URLTEST, memberIds = listOf("a", "b"))
        val rule = RoutingRule(target = RuleTarget.group("fast"), domains = listOf("domain:example.org"))
        val options = AppSettings(customRules = listOf(rule), groups = listOf(group))
            .toTunnelOptions(servers = listOf(node, a, b))
        val config = XrayConfig.build(node, options)
        val routing = config["routing"]!!.jsonObject
        val balancer = (routing["balancers"] as JsonArray).map { it.jsonObject }
            .first { it["tag"]?.jsonPrimitive?.content == "grp-fast" }
        assertEquals(listOf("srv-a", "srv-b"), balancer.strings("selector"))
        assertEquals("srv-a", balancer["fallbackTag"]?.jsonPrimitive?.content)
        val rendered = (routing["rules"] as JsonArray).map { it.jsonObject }
            .first { it.strings("domain") == listOf("domain:example.org") }
        assertEquals("grp-fast", rendered["balancerTag"]?.jsonPrimitive?.content)
        assertNotNull(config["observatory"])
    }

    @Test
    fun probeCarriesNoRoutingRuleSetsOrClashApi() {
        val options = AppSettings(
            routingPreset = RoutingPreset.BYPASS_RU,
            blockAds = true,
            customRules = listOf(RoutingRule(target = RuleTarget.Direct, clashMode = "Direct")),
        ).toTunnelOptions(clashSecret = "s").forProbe()
        val config = Json.parseToJsonElement(
            SingboxConfig.build(node, options, SingboxConfig.Inbound.LocalProxy(socks = 21000)).toString(),
        ).jsonObject
        assertNull(config["route"]!!.jsonObject["rule_set"])
        assertNull(config["experimental"])
    }

    @Test
    fun tunnelCachesRuleSetsAndFetchesThemThroughTheProxy() {
        val options = AppSettings(routingPreset = RoutingPreset.BYPASS_RU).toTunnelOptions()
        val config = Json.parseToJsonElement(SingboxConfig.build(node, options).toString()).jsonObject
        val cache = config["experimental"]!!.jsonObject["cache_file"]!!.jsonObject
        assertEquals(true, cache["enabled"]?.jsonPrimitive?.boolean)
        val sets = (config["route"]!!.jsonObject["rule_set"] as JsonArray).map { it.jsonObject }
        assertTrue(sets.all { it["download_detour"]?.jsonPrimitive?.content == "proxy" })
        // Без секрета Clash API не поднимается, даже если правило просит режим.
        assertNull(config["experimental"]!!.jsonObject["clash_api"])
    }

    @Test
    fun userAgentFollowsTheVersionUnlessChanged() {
        val versioned = dev.local.veil.AppVersion.USER_AGENT
        assertEquals("Veil/${dev.local.veil.AppVersion.NAME}", versioned)
        assertEquals(versioned, AppSettings().effectiveUserAgent)
        // Старая сборка сохранила своё значение по умолчанию — это не выбор пользователя.
        val legacy = AppSettings(userAgent = "Veil/0.1").migrated()
        assertEquals("", legacy.userAgent)
        assertEquals(versioned, legacy.effectiveUserAgent)
        assertEquals("Happ/2.0", AppSettings(userAgent = "Happ/2.0").migrated().effectiveUserAgent)
    }

    @Test
    fun lanStaysOutsideTheTunnelInGlobalMode() {
        val rules = AppSettings(routingPreset = RoutingPreset.GLOBAL).toTunnelOptions().rules
        val exclude = rules.first()
        assertEquals(RuleTarget.Direct, exclude.target)
        assertTrue("192.168.0.0/16" in exclude.ips)
    }
}
