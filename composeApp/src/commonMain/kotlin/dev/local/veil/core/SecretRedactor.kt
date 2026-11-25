package dev.local.veil.core

object SecretRedactor {
    private val authorization = Regex("(?im)(authorization\\s*[:=]\\s*)([^\\r\\n]+)")
    private val field = Regex("""(?i)(["']?(?:password|passwd|pass|uuid|token|access[-_]?token|refresh[-_]?token|private[-_]?key|public[-_]?key|preshared[-_]?key|short[-_]?id|hwid|device[-_]?id|secret|subscription[-_]?url|id)["']?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'[^']*'|[^\s,;}]+)""")
    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://[^\s<>"']+""")
    private val uuid = Regex("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6 = Regex("""(?i)(?<![\w:])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.%]+(?!\w)""")
    private val host = Regex("""(?i)\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,63}\b""")
    private val opaque = Regex("""(?<![\w])[a-zA-Z0-9_+/=-]{24,}(?![\w])""")

    fun redact(text: String): String {
        var result = authorization.replace(text) { "${it.groupValues[1]}[REDACTED]" }
        result = field.replace(result) { "${it.groupValues[1]}\"[REDACTED]\"" }
        for (pattern in listOf(url, uuid, ipv4, ipv6, host, opaque)) result = pattern.replace(result, "[REDACTED]")
        return result
    }
}
