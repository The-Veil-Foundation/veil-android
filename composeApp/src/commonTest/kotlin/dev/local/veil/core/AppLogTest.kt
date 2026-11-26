package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppLogTest {
    @Test
    fun boundedAndClearable() {
        AppLog.clear()
        repeat(600) { AppLog.event("Event $it") }
        val lines = AppLog.read().lines()
        assertEquals(500, lines.size)
        assertTrue(lines.first().endsWith("Event 100"))
        assertTrue(lines.last().endsWith("Event 599"))
        AppLog.clear()
        assertEquals("", AppLog.read())
    }
}
