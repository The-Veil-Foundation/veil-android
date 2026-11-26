package dev.local.veil.platform

expect fun readTextFile(path: String): String?

expect fun writeTextFile(path: String, text: String)

expect fun deleteFile(path: String)
