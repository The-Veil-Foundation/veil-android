package dev.local.veil.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ProfileTags {
    const val DEFAULT_SELECTOR = "proxy"
    const val DIRECT = "direct"
    const val BLOCK = "block"
    const val DNS_OUT = "dns-out"

    fun server(id: String): String = "srv-" + id.lowercase()
    fun group(id: String): String = "grp-" + id.lowercase()
    fun ruleSetGeosite(code: String): String = "geosite-" + code.lowercase()
    fun ruleSetGeoip(code: String): String = "geoip-" + code.lowercase()
}

@Serializable
data class RuleSetRef(
    val id: String = newId(),
    val tag: String,
    val kind: Kind = Kind.REMOTE,
    val format: Format = Format.BINARY,

    val url: String = "",

    val path: String = "",

    val updateInterval: String = "1d",

    val downloadDetour: String = ProfileTags.DIRECT,
) {
    @Serializable
    enum class Kind { REMOTE, LOCAL }

    @Serializable
    enum class Format { BINARY, SOURCE }

    fun json(): JsonObject? {
        when (kind) {
            Kind.REMOTE -> if (url.isBlank()) return null
            Kind.LOCAL -> if (path.isBlank()) return null
        }
        return buildJsonObject {
            put("tag", tag)
            put("type", kind.name.lowercase())
            put("format", format.name.lowercase())
            when (kind) {
                Kind.REMOTE -> {
                    put("url", url)
                    if (updateInterval.isNotBlank()) put("update_interval", updateInterval)
                    if (downloadDetour.isNotBlank()) put("download_detour", downloadDetour)
                }
                Kind.LOCAL -> put("path", path)
            }
        }
    }
}

object RuleSetCatalog {

    fun geositeUrl(code: String): String =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-${code.lowercase()}.srs"

    fun geoipUrl(code: String): String =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-${code.lowercase()}.srs"

    const val PRIVATE_GEOIP_CODE = "private"

    fun derive(
        rules: List<RoutingRule>,
        dnsRules: List<DnsRule> = emptyList(),
        explicit: List<RuleSetRef> = emptyList(),
        updateInterval: String = "1d",
        downloadDetour: String = ProfileTags.DIRECT,
    ): List<RuleSetRef> {
        val byTag = LinkedHashMap<String, RuleSetRef>()
        fun add(ref: RuleSetRef) {
            byTag[ref.tag] = ref
        }
        fun addGeosite(code: String) = add(
            RuleSetRef(
                tag = ProfileTags.ruleSetGeosite(code),
                url = geositeUrl(code),
                updateInterval = updateInterval,
                downloadDetour = downloadDetour,
            ),
        )

        for (rule in rules) {
            if (!rule.enabled) continue
            rule.domains.forEach { entry -> MatcherSyntax.geositeCode(entry)?.let(::addGeosite) }
            for (entry in rule.ips) {
                val code = MatcherSyntax.geoipCode(entry) ?: continue
                if (code.lowercase() == PRIVATE_GEOIP_CODE) continue
                add(
                    RuleSetRef(
                        tag = ProfileTags.ruleSetGeoip(code),
                        url = geoipUrl(code),
                        updateInterval = updateInterval,
                        downloadDetour = downloadDetour,
                    ),
                )
            }
        }
        for (rule in dnsRules) {
            if (!rule.enabled) continue
            rule.domains.forEach { entry -> MatcherSyntax.geositeCode(entry)?.let(::addGeosite) }
        }
        explicit.forEach(::add)
        return byTag.values.toList()
    }
}

object MatcherSyntax {

    fun geositeCode(entry: String): String? = prefixValue(entry, "geosite:")
    fun geoipCode(entry: String): String? = prefixValue(entry, "geoip:")

    private fun prefixValue(entry: String, prefix: String): String? {
        val trimmed = entry.trim()
        if (!trimmed.lowercase().startsWith(prefix)) return null
        return trimmed.drop(prefix.length).trim().ifEmpty { null }
    }

    data class Domains(
        val exact: MutableList<String> = mutableListOf(),
        val suffix: MutableList<String> = mutableListOf(),
        val keyword: MutableList<String> = mutableListOf(),
        val regex: MutableList<String> = mutableListOf(),
        val ruleSetTags: MutableList<String> = mutableListOf(),
    ) {
        val isEmpty: Boolean
            get() = exact.isEmpty() && suffix.isEmpty() && keyword.isEmpty() &&
                regex.isEmpty() && ruleSetTags.isEmpty()
    }

    fun domains(entries: List<String>): Domains {
        val out = Domains()
        for (raw in entries) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val lower = entry.lowercase()
            val geosite = geositeCode(entry)
            when {
                geosite != null -> out.ruleSetTags += ProfileTags.ruleSetGeosite(geosite)
                lower.startsWith("full:") -> out.exact += entry.drop(5)
                lower.startsWith("domain:") -> appendSubdomain(entry.drop(7), out)
                lower.startsWith("keyword:") -> out.keyword += entry.drop(8)
                lower.startsWith("regexp:") -> out.regex += entry.drop(7)
                lower.startsWith("ext:") -> Unit
                else -> appendSubdomain(entry, out)
            }
        }
        return out
    }

    private fun appendSubdomain(value: String, out: Domains) {
        val host = value.trim().trim('.')
        if (host.isEmpty()) return
        out.exact += host
        out.suffix += ".$host"
    }

    data class Ips(
        val cidrs: MutableList<String> = mutableListOf(),
        val ruleSetTags: MutableList<String> = mutableListOf(),
        var isPrivate: Boolean = false,
    ) {
        val isEmpty: Boolean get() = cidrs.isEmpty() && ruleSetTags.isEmpty() && !isPrivate
    }

    fun ips(entries: List<String>): Ips {
        val out = Ips()
        for (raw in entries) {
            val entry = raw.trim()
            if (entry.isEmpty()) continue
            val code = geoipCode(entry)
            when {
                code == null -> out.cidrs += entry
                code.lowercase() == RuleSetCatalog.PRIVATE_GEOIP_CODE -> out.isPrivate = true
                else -> out.ruleSetTags += ProfileTags.ruleSetGeoip(code)
            }
        }
        return out
    }

    fun ports(text: String): Pair<List<Int>, List<String>> {
        val ports = mutableListOf<Int>()
        val ranges = mutableListOf<String>()
        text.split(',', ' ', '\t', '\n').filter { it.isNotEmpty() }.forEach { piece ->
            val single = port(piece)
            if (single != null) {
                ports += single
            } else {
                portRange(piece)?.let { ranges += it }
            }
        }
        return ports to ranges
    }

    private fun port(token: String): Int? {
        if (token.isEmpty() || !token.all { it in '0'..'9' }) return null
        return token.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun portRange(token: String): String? {
        val colon = token.contains(':')
        val parts = token.split(if (colon) ':' else '-')
        if (parts.size != 2) return null
        val (lower, upper) = parts
        if (lower.isEmpty() && upper.isEmpty()) return null

        if ((lower.isEmpty() || upper.isEmpty()) && !colon) return null
        val low = if (lower.isEmpty()) null else port(lower) ?: return null
        val high = if (upper.isEmpty()) null else port(upper) ?: return null
        if (low != null && high != null && low > high) return null
        return "${low ?: ""}:${high ?: ""}"
    }

    fun xrayPorts(text: String): String? {
        val (ports, ranges) = ports(text)
        val pieces = ports.map { it.toString() } + ranges.map { range ->
            val (low, high) = range.split(':')
            "${low.ifEmpty { "1" }}-${high.ifEmpty { "65535" }}"
        }
        return pieces.joinToString(",").ifEmpty { null }
    }
}
