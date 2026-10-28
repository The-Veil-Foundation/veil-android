package dev.local.veil.core

/* ------------------------------------------------------------------ */
/* base64                                                              */
/* ------------------------------------------------------------------ */

private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun base64DecodeBytes(input: String): ByteArray {
    val s = input.trim().replace('-', '+').replace('_', '/')
    val out = ArrayList<Byte>(s.length * 3 / 4 + 3)
    var buffer = 0
    var bits = 0
    for (ch in s) {
        if (ch == '=') break
        if (ch.isWhitespace()) continue
        val v = B64.indexOf(ch)
        if (v < 0) throw LinkParseException("Некорректный base64")
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((buffer shr bits) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}

fun base64Decode(input: String): String =
    try {
        base64DecodeBytes(input).decodeToString()
    } catch (e: Exception) {
        throw LinkParseException("Некорректный base64")
    }

/* ------------------------------------------------------------------ */
/* percent-encoding                                                    */
/* ------------------------------------------------------------------ */

fun percentDecode(input: String): String {
    if ('%' !in input) return input
    val sb = StringBuilder(input.length)
    val pending = ArrayList<Byte>()
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '%' && i + 2 < input.length) {
            val value = input.substring(i + 1, i + 3).toIntOrNull(16)
            if (value == null) {
                sb.append(c)
                i++
            } else {
                pending.add(value.toByte())
                i += 3
            }
        } else {
            if (pending.isNotEmpty()) {
                sb.append(pending.toByteArray().decodeToString())
                pending.clear()
            }
            sb.append(c)
            i++
        }
    }
    if (pending.isNotEmpty()) sb.append(pending.toByteArray().decodeToString())
    return sb.toString()
}

/* ------------------------------------------------------------------ */
/* uri                                                                 */
/* ------------------------------------------------------------------ */

internal data class RawUri(
    val scheme: String,
    val userInfo: String,
    val host: String,
    val port: Int,
    val path: String,
    val query: Map<String, String>,
    val fragment: String,
)

internal fun splitHostPort(authority: String): Pair<String, Int> {
    if (authority.startsWith("[")) {
        val end = authority.indexOf(']')
        if (end < 0) throw LinkParseException("Некорректный IPv6-адрес: $authority")
        val host = authority.substring(1, end)
        val port = authority.substring(end + 1).removePrefix(":").toIntOrNull()
            ?: throw LinkParseException("Не указан порт: $authority")
        return host to port
    }
    val idx = authority.lastIndexOf(':')
    if (idx <= 0) throw LinkParseException("Не указан порт: $authority")
    val host = authority.substring(0, idx)
    val port = authority.substring(idx + 1).toIntOrNull()
        ?: throw LinkParseException("Некорректный порт: $authority")
    if (port !in 1..65535) throw LinkParseException("Порт вне диапазона: $port")
    return host to port
}

private fun parseQuery(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val result = LinkedHashMap<String, String>()
    for (pair in raw.split('&')) {
        if (pair.isBlank()) continue
        val eq = pair.indexOf('=')
        if (eq < 0) {
            result[percentDecode(pair)] = ""
        } else {
            result[percentDecode(pair.substring(0, eq))] = percentDecode(pair.substring(eq + 1))
        }
    }
    return result
}

internal fun parseRawUri(raw: String): RawUri {
    val schemeEnd = raw.indexOf("://")
    if (schemeEnd <= 0) throw LinkParseException("Это не share-ссылка")

    val scheme = raw.substring(0, schemeEnd).lowercase()
    val rest = raw.substring(schemeEnd + 3)

    val hashIdx = rest.indexOf('#')
    val fragment = if (hashIdx >= 0) percentDecode(rest.substring(hashIdx + 1)) else ""
    val noFragment = if (hashIdx >= 0) rest.substring(0, hashIdx) else rest

    val qIdx = noFragment.indexOf('?')
    val queryString = if (qIdx >= 0) noFragment.substring(qIdx + 1) else ""
    val noQuery = if (qIdx >= 0) noFragment.substring(0, qIdx) else noFragment

    val slashIdx = noQuery.indexOf('/')
    val authority = if (slashIdx >= 0) noQuery.substring(0, slashIdx) else noQuery
    val path = if (slashIdx >= 0) percentDecode(noQuery.substring(slashIdx)) else ""

    val atIdx = authority.lastIndexOf('@')
    val userInfo = if (atIdx >= 0) authority.substring(0, atIdx) else ""
    val hostPort = if (atIdx >= 0) authority.substring(atIdx + 1) else authority

    val (host, port) = splitHostPort(hostPort)
    if (host.isBlank()) throw LinkParseException("Пустой адрес сервера")

    return RawUri(scheme, userInfo, host, port, path, parseQuery(queryString), fragment)
}

/** Детерминированный id: одна и та же ссылка → один и тот же профиль. */
internal fun hashId(input: String): String {
    var hash = 0xcbf29ce484222325UL
    for (ch in input) {
        hash = hash xor ch.code.toULong()
        hash *= 0x100000001b3UL
    }
    return hash.toString(36)
}
