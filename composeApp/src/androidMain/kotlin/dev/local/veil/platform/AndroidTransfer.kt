package dev.local.veil.platform

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import dev.local.veil.core.Pairing
import dev.local.veil.core.TransferPayload
import dev.local.veil.core.percentDecode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Перенос подписок на приставку по локальной сети.
 *
 * Приставка поднимает крошечный HTTP-сервер на случайном порту и показывает
 * QR с адресом и одноразовым токеном. Телефон сканирует его в Veil и
 * отправляет подписки; либо открывает адрес камерой и вставляет ссылку на
 * странице. Сервер работает, пока открыт экран приёма.
 */
class AndroidTransferService(private val context: Context) : TransferService {

    override val isTelevision: Boolean
        get() = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

    override fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    override fun startReceiving(onReceived: (TransferPayload) -> Unit): ReceiveSession? {
        val address = lanAddress() ?: return null
        val token = randomToken()
        val server = runCatching { PairServer(token, onReceived) }.getOrNull() ?: return null
        server.start()
        return ReceiveSession("http://$address:${server.port}/pair/$token") { server.stop() }
    }

    override suspend fun send(pairingUrl: String, payload: TransferPayload): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(Pairing.parse(pairingUrl) != null) { "Это не адрес приёма" }
                val uri = URI(pairingUrl)
                val body = Json.encodeToString(TransferPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
                // Сырой сокет, а не HttpURLConnection: Android запрещает обычный
                // HTTP без шифрования, а здесь он осознанно только внутри сети.
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(uri.host, uri.port), 5000)
                    socket.soTimeout = 8000
                    val head = "POST ${uri.path} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n" +
                        "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(head.toByteArray(Charsets.UTF_8))
                        write(body)
                        flush()
                    }
                    val status = socket.getInputStream().bufferedReader().readLine().orEmpty()
                    check(status.contains(" 200")) { "Приставка отклонила данные: $status" }
                }
                Unit
            }.recoverCatching {
                throw IllegalStateException(
                    "Не удалось отправить. Телефон и приставка в одной сети Wi-Fi? (${it.message})",
                )
            }
        }

    private fun randomToken(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Адрес в домашней сети; VPN-интерфейсы и мобильные пропускаем. */
    private fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .sortedBy { iface ->
                val name = iface.name.lowercase()
                when {
                    name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("en") -> 0
                    name.startsWith("tun") || name.startsWith("ppp") || name.startsWith("rmnet") -> 2
                    else -> 1
                }
            }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}

private class PairServer(
    private val token: String,
    private val onPayload: (TransferPayload) -> Unit,
) {
    private val socket = ServerSocket(0, 8)

    @Volatile
    private var running = true

    val port: Int get() = socket.localPort

    fun start() {
        thread(isDaemon = true, name = "veil-pair") {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                try {
                    handle(client)
                } catch (_: Exception) {
                    // Битый запрос не должен ронять приём.
                } finally {
                    runCatching { client.close() }
                }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket.close() }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')

        var length = 0
        var contentType = ""
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val value = line.substringAfter(':', "").trim()
            when (line.substringBefore(':').trim().lowercase()) {
                "content-length" -> length = value.toIntOrNull() ?: 0
                "content-type" -> contentType = value.lowercase()
            }
        }

        // Токен в пути — единственная защита, сравниваем без утечки по времени.
        val expected = "/pair/$token".toByteArray()
        if (!MessageDigest.isEqual(path.toByteArray(), expected)) {
            respond(client, 404, "text/plain; charset=utf-8", "Не найдено")
            return
        }

        when (method) {
            "GET" -> respond(client, 200, "text/html; charset=utf-8", PAGE)
            "POST" -> {
                if (length !in 1..MAX_BODY) {
                    respond(client, 413, "text/plain; charset=utf-8", "Слишком большой запрос")
                    return
                }
                val body = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(body, read, length - read)
                    if (n < 0) break
                    read += n
                }
                val payload = parsePayload(String(body, 0, read, Charsets.UTF_8), contentType)
                if (payload == null || payload.isEmpty) {
                    respond(client, 400, "text/html; charset=utf-8", RESULT.replace("{{msg}}", "Ничего не распознано. Нужны ссылки на подписки или vless://, vmess://… ссылки."))
                } else {
                    onPayload(payload)
                    respond(
                        client, 200, "text/html; charset=utf-8",
                        RESULT.replace("{{msg}}", "Готово: подписок ${payload.subscriptions.size}, серверов ${payload.servers.size}. Можно закрыть страницу."),
                    )
                }
            }
            else -> respond(client, 405, "text/plain; charset=utf-8", "Метод не поддерживается")
        }
    }

    private fun parsePayload(text: String, contentType: String): TransferPayload? = when {
        "json" in contentType -> runCatching {
            Json { ignoreUnknownKeys = true }.decodeFromString(TransferPayload.serializer(), text)
        }.getOrNull()
        "x-www-form-urlencoded" in contentType -> {
            val data = text.split('&')
                .firstOrNull { it.startsWith("data=") }
                ?.removePrefix("data=")
                ?.let { runCatching { percentDecode(it.replace('+', ' ')) }.getOrNull() }
            data?.let { Pairing.fromText(it) }
        }
        else -> Pairing.fromText(text)
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < 8192) {
            val b = input.read()
            if (b < 0) return if (buffer.size() == 0) null else buffer.toString("UTF-8")
            if (b == '\n'.code) break
            if (b != '\r'.code) buffer.write(b)
        }
        return buffer.toString("UTF-8")
    }

    private fun respond(client: Socket, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Payload Too Large"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private companion object {
        const val MAX_BODY = 256 * 1024

        const val STYLE = "body{font-family:system-ui,sans-serif;margin:0;padding:24px;max-width:560px;margin:auto;" +
            "background:#0f172a;color:#e2e8f0}h1{font-size:22px}textarea{width:100%;height:180px;box-sizing:border-box;" +
            "border-radius:16px;border:1px solid #475569;background:#1e293b;color:#e2e8f0;padding:12px;font-size:15px}" +
            "button{margin-top:12px;width:100%;padding:14px;border:0;border-radius:999px;background:#7dd3fc;color:#0c4a6e;" +
            "font-size:16px;font-weight:700}p{color:#94a3b8}"

        const val PAGE = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Veil</title>" +
            "<style>$STYLE</style></head><body><h1>Veil: перенос на эту приставку</h1>" +
            "<p>Вставьте ссылку на подписку или ссылки серверов, по одной в строке.</p>" +
            "<form method=\"post\"><textarea name=\"data\" placeholder=\"https://…\" autofocus></textarea>" +
            "<button type=\"submit\">Отправить</button></form></body></html>"

        const val RESULT = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Veil</title>" +
            "<style>$STYLE</style></head><body><h1>Veil</h1><p>{{msg}}</p></body></html>"
    }
}
