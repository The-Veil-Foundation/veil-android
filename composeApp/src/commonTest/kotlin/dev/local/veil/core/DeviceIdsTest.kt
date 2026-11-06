package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceIdsTest {
    @Test
    fun mintedIdMatchesMacFormat() {
        repeat(50) {
            val id = DeviceIds.mint()
            assertEquals(16, id.length)
            assertTrue(id.all { it in '0'..'9' || it in 'A'..'F' }, id)
        }
    }

    @Test
    fun manualIdIsTrimmedAndUppercased() {
        assertEquals("ABC-123", DeviceIds.normalizedManual("  abc-123\n"))
        assertNull(DeviceIds.normalizedManual("   "))
    }
}
