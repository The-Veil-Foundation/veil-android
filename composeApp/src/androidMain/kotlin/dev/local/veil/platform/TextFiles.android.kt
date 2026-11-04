package dev.local.veil.platform

import java.io.File

actual fun readTextFile(path: String): String? =
    runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()

actual fun writeTextFile(path: String, text: String) {
    runCatching {
        val file = File(path)
        file.parentFile?.mkdirs()
        file.writeText(text)
    }
}

actual fun deleteFile(path: String) {
    runCatching { File(path).deleteRecursively() }
}
