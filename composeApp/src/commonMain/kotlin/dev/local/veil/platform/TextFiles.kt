package dev.local.veil.platform

/** Чтение текстового файла; null — файла нет или он недоступен. */
expect fun readTextFile(path: String): String?

/** Запись текстового файла, при необходимости создаёт каталоги. */
expect fun writeTextFile(path: String, text: String)

/** Удаление файла или каталога; отсутствие файла не считается ошибкой. */
expect fun deleteFile(path: String)
