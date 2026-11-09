package dev.local.veil.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFails

class GeoFileTest {
    private fun checkBytes(bytes: ByteArray, valid: Boolean) {
        val file = File.createTempFile("veil-geo-test", ".dat")
        try {
            file.writeBytes(bytes)
            if (valid) validateGeoFile(file) else assertFails { validateGeoFile(file) }
        } finally { file.delete() }
    }

    @Test fun acceptsRepeatedEntries() = checkBytes(byteArrayOf(10, 3, 10, 1, 65, 10, 3, 10, 1, 66), true)
    @Test fun rejectsHtmlErrorPages() = checkBytes("<html>Access denied</html>".toByteArray(), false)
    @Test fun rejectsEmptyFiles() = checkBytes(byteArrayOf(), false)
    @Test fun rejectsTruncatedEntries() = checkBytes(byteArrayOf(10, 4, 10, 1, 65), false)
    @Test fun rejectsBrokenLength() = checkBytes(byteArrayOf(10, -128), false)
}
