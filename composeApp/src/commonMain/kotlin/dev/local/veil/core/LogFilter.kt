package dev.local.veil.core

enum class LogSeverity { DEBUG, INFO, WARNING, ERROR, FATAL }

data class LogLine(val index: Int, val text: String, val severity: LogSeverity?)

object LogFilter {

    private val bracketed = listOf(
        "[debug]" to LogSeverity.DEBUG, "[info]" to LogSeverity.INFO,
        "[warning]" to LogSeverity.WARNING, "[warn]" to LogSeverity.WARNING,
        "[error]" to LogSeverity.ERROR, "[fatal]" to LogSeverity.FATAL, "[panic]" to LogSeverity.FATAL,
    )

    private val bare = mapOf(
        "DEBUG" to LogSeverity.DEBUG, "TRACE" to LogSeverity.DEBUG, "INFO" to LogSeverity.INFO,
        "WARN" to LogSeverity.WARNING, "WARNING" to LogSeverity.WARNING,
        "ERROR" to LogSeverity.ERROR, "FATAL" to LogSeverity.FATAL, "PANIC" to LogSeverity.FATAL,
    )

    fun severityOf(line: String): LogSeverity? {
        val lower = line.lowercase()
        bracketed.firstOrNull { lower.contains(it.first) }?.let { return it.second }

        return line.split(Regex("[^A-Za-z]+")).firstNotNullOfOrNull { bare[it] }
    }

    fun parse(text: String): List<LogLine> {
        if (text.isEmpty()) return emptyList()
        var inherited: LogSeverity? = null
        return text.trimEnd('\n').split('\n').mapIndexed { index, line ->
            val own = severityOf(line)
            if (own != null) inherited = own
            LogLine(index, line, own ?: inherited)
        }
    }

    fun apply(lines: List<LogLine>, query: String, minimum: LogSeverity?): List<LogLine> {
        val needle = query.trim()
        if (needle.isEmpty() && minimum == null) return lines
        return lines.filter { line ->

            (minimum == null || line.severity == null || line.severity >= minimum) &&
                (needle.isEmpty() || line.text.contains(needle, ignoreCase = true))
        }
    }
}
