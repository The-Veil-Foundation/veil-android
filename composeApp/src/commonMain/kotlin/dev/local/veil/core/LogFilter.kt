package dev.local.veil.core

/**
 * Severity of a core log line, lowest first. A port of LogFilter from
 * veil-macos: Xray writes `[Warning]`, sing-box a bare `WARN`.
 */
enum class LogSeverity { DEBUG, INFO, WARNING, ERROR, FATAL }

/** One line of a core log, with the severity it announces or inherits. */
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

    /** The severity this line announces, or null when it announces none. */
    fun severityOf(line: String): LogSeverity? {
        val lower = line.lowercase()
        bracketed.firstOrNull { lower.contains(it.first) }?.let { return it.second }
        // sing-box prints the level as a bare uppercase word, so only an
        // all-caps token counts: "Error" inside a sentence is prose.
        return line.split(Regex("[^A-Za-z]+")).firstNotNullOfOrNull { bare[it] }
    }

    /**
     * Splits [text] into lines. A line with no level of its own belongs to the
     * line above it: the second half of "config failed:" is the reason for the
     * error, and hiding it hides why the error happened.
     */
    fun parse(text: String): List<LogLine> {
        if (text.isEmpty()) return emptyList()
        var inherited: LogSeverity? = null
        return text.trimEnd('\n').split('\n').mapIndexed { index, line ->
            val own = severityOf(line)
            if (own != null) inherited = own
            LogLine(index, line, own ?: inherited)
        }
    }

    /** The lines that pass both filters, in the order they arrived. */
    fun apply(lines: List<LogLine>, query: String, minimum: LogSeverity?): List<LogLine> {
        val needle = query.trim()
        if (needle.isEmpty() && minimum == null) return lines
        return lines.filter { line ->
            // A line with no level at all stays, as on macOS: it is not below the floor.
            (minimum == null || line.severity == null || line.severity >= minimum) &&
                (needle.isEmpty() || line.text.contains(needle, ignoreCase = true))
        }
    }
}
