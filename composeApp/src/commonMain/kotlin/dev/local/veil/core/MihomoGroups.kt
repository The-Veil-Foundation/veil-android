package dev.local.veil.core

object MihomoGroups {
    data class Result(val groups: List<ServerGroup>, val skipped: List<String>)

    fun parse(text: String, nodes: List<ProxyConfig>): Result {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == "proxy-groups:" }
        if (start < 0) return Result(emptyList(), emptyList())
        val base = lines[start].takeWhile { it == ' ' }.length
        val records = mutableListOf<Pair<Map<String, String>, List<String>>>()
        var fields = linkedMapOf<String, String>()
        var members = mutableListOf<String>()
        var itemIndent = -1
        var readingMembers = false
        fun flush() {
            if (fields.isNotEmpty()) records += fields.toMap() to members.toList()
            fields = linkedMapOf()
            members = mutableListOf()
            readingMembers = false
        }
        for (line in lines.drop(start + 1)) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            val indent = line.takeWhile { it == ' ' }.length
            if (indent <= base && !trimmed.startsWith("- ")) break
            if (trimmed.startsWith("- ") && (itemIndent < 0 || indent == itemIndent)) {
                flush()
                itemIndent = indent
            } else if (trimmed.startsWith("- ") && readingMembers) {
                members += scalar(trimmed.removePrefix("- "))
                continue
            }
            val pair = trimmed.removePrefix("- ").split(':', limit = 2)
            if (pair.size != 2) continue
            val key = pair[0].trim()
            val value = pair[1].trim()
            readingMembers = key == "proxies"
            if (readingMembers && value.startsWith('[') && value.endsWith(']')) {
                members += splitList(value.substring(1, value.lastIndex))
            } else fields[key] = scalar(value)
        }
        flush()
        val byName = nodes.groupBy { it.name }
        val groups = mutableListOf<ServerGroup>()
        val skipped = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for ((raw, names) in records) {
            val name = raw["name"].orEmpty()
            val kind = when (raw["type"]) {
                "select" -> GroupKind.SELECTOR
                "url-test" -> GroupKind.URLTEST
                else -> null
            }
            val invalid = name.isBlank() || !seen.add(name) || kind == null || names.isEmpty() ||
                names.any { byName[it]?.size != 1 } || raw.keys.any { it in setOf("use", "include-all", "filter", "exclude-filter") }
            if (invalid) {
                skipped += "Mihomo group ${name.ifBlank { "unnamed" }}: unsupported type, provider, nested/built-in/ambiguous member or empty list"
                continue
            }
            val ids = names.map { byName.getValue(it).single().id }.distinct()
            groups += ServerGroup(
                name = name, kind = kind!!, memberIds = ids, selectedId = ids.first(), sourceName = name,
                testUrl = raw["url"].orEmpty().ifBlank { "https://www.gstatic.com/generate_204" },
                interval = "${raw["interval"]?.toIntOrNull()?.coerceAtLeast(1) ?: 180}s",
                tolerance = raw["tolerance"]?.toIntOrNull()?.coerceAtLeast(0) ?: 50,
            )
        }
        return Result(groups, skipped)
    }

    private fun scalar(value: String): String {
        val text = value.trim()
        if (text.length >= 2 && text.first() == text.last() && text.first() in "\"'") {
            return text.substring(1, text.lastIndex)
        }
        return text.substringBefore(" #").trim()
    }

    private fun splitList(value: String): List<String> {
        var quote: Char? = null
        var escaped = false
        val parts = mutableListOf<String>()
        val part = StringBuilder()
        for (char in value) {
            if (escaped) { part.append(char); escaped = false; continue }
            if (char == '\\' && quote == '"') { part.append(char); escaped = true; continue }
            if (char in "\"'" && (quote == null || quote == char)) quote = if (quote == null) char else null
            if (char == ',' && quote == null) { parts += scalar(part.toString()); part.clear() }
            else part.append(char)
        }
        if (part.isNotBlank()) parts += scalar(part.toString())
        return parts
    }
}
