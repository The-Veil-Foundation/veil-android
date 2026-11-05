package dev.local.veil.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/* ------------------------------------------------------------------ */
/* Порт RuleSet.swift из veil-macos                                    */
/* ------------------------------------------------------------------ */

/**
 * Теги выходов, общие для всех сборщиков конфига.
 *
 * Теги должны быть стабильны между пересборками — сохранённое правило ссылается
 * на них, — поэтому строятся из id узла или группы, а не из имени.
 */
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

/**
 * Rule-set sing-box: замена полей `geosite:` / `geoip:`, которые убрали из
 * route-правил в sing-box 1.12. Удалённые наборы ядро качает и кэширует само.
 */
@Serializable
data class RuleSetRef(
    val id: String = newId(),
    val tag: String,
    val kind: Kind = Kind.REMOTE,
    val format: Format = Format.BINARY,
    /** Только для удалённых. */
    val url: String = "",
    /** Только для локальных. */
    val path: String = "",
    /** Как часто sing-box обновляет удалённый набор. */
    val updateInterval: String = "1d",
    /** Через какой выход качать удалённый набор. Пусто — решает ядро. */
    val downloadDetour: String = ProfileTags.DIRECT,
) {
    @Serializable
    enum class Kind { REMOTE, LOCAL }

    @Serializable
    enum class Format { BINARY, SOURCE }

    /** Элемент `route.rule_set[]`, или null, если ссылка пустая. */
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

/**
 * Превращает `geosite:` / `geoip:` из списка правил в конкретные rule-set.
 * Репозитории SagerNet публикуют готовый `.srs` на каждую категорию.
 */
object RuleSetCatalog {

    fun geositeUrl(code: String): String =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-${code.lowercase()}.srs"

    fun geoipUrl(code: String): String =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-${code.lowercase()}.srs"

    /** У `geoip:private` есть родной матчер (`ip_is_private`), rule-set не нужен. */
    const val PRIVATE_GEOIP_CODE = "private"

    /**
     * Все rule-set, которые подразумевают правила, без повторов и по порядку.
     * Явные (заданные руками) побеждают выведенные с тем же тегом.
     */
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

/**
 * Раскладывает матчеры в Xray-синтаксисе по типизированным полям sing-box.
 *
 * Голая запись `example.com` у Xray — подстрока, но каждый GUI имеет в виду
 * «этот домен и поддомены». sing-box получает второе прочтение
 * (`domain` + `domain_suffix`).
 */
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
                lower.startsWith("ext:") -> Unit // внешний .dat: только Xray
                else -> appendSubdomain(entry, out)
            }
        }
        return out
    }

    /** «Домен и всё под ним»: апекс точно, суффикс с ведущей точкой. */
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

    /**
     * Порты для sing-box: одиночные в `port`, диапазоны в `port_range`.
     * Принимает "443", "443,8443", "1000-2000", "1000:2000", ":1024".
     * Диапазоны выходят как "a:b"; всё, что ядро отвергло бы, отбрасывается,
     * чтобы одна опечатка не мешала старту.
     */
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
        // "a-" и "-b" — опечатка или отрицательное число; открытый конец только через двоеточие.
        if ((lower.isEmpty() || upper.isEmpty()) && !colon) return null
        val low = if (lower.isEmpty()) null else port(lower) ?: return null
        val high = if (upper.isEmpty()) null else port(upper) ?: return null
        if (low != null && high != null && low > high) return null
        return "${low ?: ""}:${high ?: ""}"
    }

    /**
     * Порты в Xray-формате: "443,1000-2000". Xray понимает только дефис,
     * открытые концы заменяются на 1 и 65535.
     */
    fun xrayPorts(text: String): String? {
        val (ports, ranges) = ports(text)
        val pieces = ports.map { it.toString() } + ranges.map { range ->
            val (low, high) = range.split(':')
            "${low.ifEmpty { "1" }}-${high.ifEmpty { "65535" }}"
        }
        return pieces.joinToString(",").ifEmpty { null }
    }
}
