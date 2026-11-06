package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LogFilterTest {

    private val text = """
        2026/11/06 19:41:03 [Info] core: Xray 25.1 started
        2026/11/06 19:41:04 [Warning] transport: dial failed
        +0300 2026-11-06 19:41:05 ERROR [123 0ms] outbound/vless[proxy]: connection reset
          caused by: EOF
        +0300 2026-11-06 19:41:06 INFO inbound/tun: Error-free line
    """.trimIndent()

    @Test
    fun readsBothCoresLevels() {
        val lines = LogFilter.parse(text)
        assertEquals(
            listOf(LogSeverity.INFO, LogSeverity.WARNING, LogSeverity.ERROR, LogSeverity.ERROR, LogSeverity.INFO),
            lines.map { it.severity },
        )
    }

    @Test
    fun continuationFollowsItsErrorThroughTheFloor() {
        val shown = LogFilter.apply(LogFilter.parse(text), "", LogSeverity.ERROR)
        assertEquals(2, shown.size)
        assertEquals("  caused by: EOF", shown[1].text)
    }

    @Test
    fun queryIsCaseInsensitive() {
        val shown = LogFilter.apply(LogFilter.parse(text), "DIAL", null)
        assertEquals(1, shown.size)
    }
}
