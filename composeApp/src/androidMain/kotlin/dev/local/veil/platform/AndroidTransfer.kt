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
        // Эмулятор сидит за NAT Мака и видит только 10.0.2.15, до которого
        // телефон не дойдёт. Для проверки адрес и порт подменяются снаружи:
        // adb shell setprop debug.veil.pair_host 192.168.x.y (+ pair_port).
        // Свойства debug.* ставит только adb, обычному пользователю они пусты.
        val address = systemProperty("debug.veil.pair_host") ?: lanAddress() ?: return null
        val fixedPort = systemProperty("debug.veil.pair_port")?.toIntOrNull() ?: 0
        val token = randomToken()
        val server = runCatching { PairServer(token, onReceived, fixedPort) }.getOrNull() ?: return null
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

    private fun systemProperty(name: String): String? = runCatching {
        Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().use { it.readText().trim() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

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
    port: Int = 0,
) {
    private val socket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(port), 8)
    }

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
                    respond(client, 400, "text/html; charset=utf-8", result(false, "Ничего не распознано", "Нужна ссылка на подписку или ссылки vless://, vmess://… по одной в строке."))
                } else {
                    onPayload(payload)
                    respond(
                        client, 200, "text/html; charset=utf-8",
                        result(true, "Отправлено", "Подписок: ${payload.subscriptions.size}, серверов: ${payload.servers.size}. Посмотрите на телевизор — страницу можно закрыть."),
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

        const val PAGE = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light dark\"><title>Veil</title><style>:root{color-scheme:light dark;--bg:#f9f9ff;--surface:#ffffff;--surface2:#eef0f9;--on:#191c20;--muted:#44474e;--outline:#c4c6d0;--primary:#4a5c92;--on-primary:#ffffff;--tonal:#dbe1ff;--on-tonal:#32457a;--error:#ba1a1a;--ok:#2e6b2e}@media(prefers-color-scheme:dark){:root{--bg:#111318;--surface:#1d2024;--surface2:#272a2f;--on:#e2e2e9;--muted:#c4c6d0;--outline:#44474e;--primary:#b4c5ff;--on-primary:#1a2d60;--tonal:#32457a;--on-tonal:#dbe1ff;--error:#ffb4ab;--ok:#a3d39b}}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--on);font:16px/1.5 Roboto,\"Google Sans\",system-ui,-apple-system,sans-serif;-webkit-tap-highlight-color:transparent}main{max-width:520px;margin:0 auto;padding:28px 16px 40px}.brand{display:flex;align-items:center;gap:12px;margin-bottom:20px}.logo{width:44px;height:44px;border-radius:14px;background:var(--tonal);display:grid;place-items:center}.logo svg{width:24px;height:24px;fill:var(--on-tonal)}.brand b{font-size:15px;letter-spacing:.2px;color:var(--muted);font-weight:500}h1{font-size:28px;line-height:36px;font-weight:600;margin:0 0 8px}.lead{color:var(--muted);margin:0 0 24px}.card{background:var(--surface);border-radius:28px;padding:20px;margin-bottom:12px;box-shadow:0 1px 2px rgba(0,0,0,.08)}@media(prefers-color-scheme:dark){.card{box-shadow:none;background:var(--surface)}}.card h2{font-size:18px;font-weight:600;margin:0 0 4px}.card p{color:var(--muted);margin:0 0 16px;font-size:14px}.btn{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;height:56px;border:0;border-radius:999px;font:600 16px/1 inherit;font-family:inherit;text-decoration:none;cursor:pointer;transition:transform .12s,filter .12s}.btn:active{transform:scale(.98);filter:brightness(.92)}.filled{background:var(--primary);color:var(--on-primary)}.tonal{background:var(--tonal);color:var(--on-tonal)}textarea{width:100%;min-height:140px;resize:vertical;border:1px solid var(--outline);border-radius:16px;background:transparent;color:var(--on);padding:14px 16px;font:14px/1.45 ui-monospace,\"Roboto Mono\",monospace;margin-bottom:12px;outline:none}textarea:focus{border:2px solid var(--primary);padding:13px 15px}.note{display:flex;gap:10px;align-items:flex-start;color:var(--muted);font-size:13px;margin-top:20px;padding:0 4px}.note svg{flex:none;width:18px;height:18px;fill:var(--muted);margin-top:2px}.state{text-align:center;padding:32px 20px}.state .icon{width:72px;height:72px;border-radius:24px;margin:0 auto 16px;display:grid;place-items:center;background:var(--tonal)}.state .icon svg{width:36px;height:36px;fill:var(--on-tonal)}.state.err .icon{background:color-mix(in srgb,var(--error) 18%,transparent)}.state.err .icon svg{fill:var(--error)}[hidden]{display:none!important}</style></head><body><main><div class=\"brand\"><div class=\"logo\"><svg viewBox=\"0 0 24 24\"><path d=\"M12 2 4 5v6c0 5.25 3.4 10.15 8 11.5 4.6-1.35 8-6.25 8-11.5V5l-8-3zm0 2.2 6 2.25V11c0 4.15-2.55 8.1-6 9.4V4.2z\"/></svg></div><b>Veil</b></div><h1>Перенос на приставку</h1><p class=\"lead\">Отправьте подписки с этого телефона на телевизор.</p><section class=\"card\" id=\"app\" hidden><h2>Veil на этом телефоне</h2><p>Откроется приложение и спросит, какие подписки отправить.</p><a class=\"btn filled\" id=\"open\" href=\"#\"><svg viewBox=\"0 0 24 24\" width=\"20\" height=\"20\" fill=\"currentColor\"><path d=\"M14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7zm5 16H5V5h7V3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7h-2v7z\"/></svg>Открыть в Veil</a></section><section class=\"card\"><h2 id=\"or\">Вставьте ссылку</h2><p>Ссылка на подписку или ссылки серверов, по одной в строке.</p><form method=\"post\"><textarea name=\"data\" placeholder=\"https://…&#10;vless://…\" spellcheck=\"false\" autocapitalize=\"off\"></textarea><button class=\"btn tonal\" type=\"submit\">Отправить на приставку</button></form></section><div class=\"note\"><svg viewBox=\"0 0 24 24\"><path d=\"M12 1a5 5 0 0 0-5 5v3H6a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-9a2 2 0 0 0-2-2h-1V6a5 5 0 0 0-5-5zm-3 8V6a3 3 0 1 1 6 0v3H9z\"/></svg><span>Данные идут напрямую на приставку в вашей сети, без интернета. Адрес работает, пока на телевизоре открыт экран приёма.</span></div></main><script>(function(){var ua=navigator.userAgent;if(!/Android/i.test(ua))return;var here=location.href.split('#')[0];var intent='intent://pair?u='+encodeURIComponent(here)+'#Intent;scheme=veil;package=org.veil.app;S.browser_fallback_url='+encodeURIComponent(here+'#manual')+';end';var card=document.getElementById('app'),btn=document.getElementById('open');btn.href=intent;card.hidden=false;document.getElementById('or').textContent='Или вставьте ссылку';if(location.hash!=='#manual'){try{location.href=intent}catch(e){}}})()</script></body></html>"

        private const val RESULT = "<!doctype html><html lang=\"ru\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"light dark\"><title>Veil</title><style>:root{color-scheme:light dark;--bg:#f9f9ff;--surface:#ffffff;--surface2:#eef0f9;--on:#191c20;--muted:#44474e;--outline:#c4c6d0;--primary:#4a5c92;--on-primary:#ffffff;--tonal:#dbe1ff;--on-tonal:#32457a;--error:#ba1a1a;--ok:#2e6b2e}@media(prefers-color-scheme:dark){:root{--bg:#111318;--surface:#1d2024;--surface2:#272a2f;--on:#e2e2e9;--muted:#c4c6d0;--outline:#44474e;--primary:#b4c5ff;--on-primary:#1a2d60;--tonal:#32457a;--on-tonal:#dbe1ff;--error:#ffb4ab;--ok:#a3d39b}}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--on);font:16px/1.5 Roboto,\"Google Sans\",system-ui,-apple-system,sans-serif;-webkit-tap-highlight-color:transparent}main{max-width:520px;margin:0 auto;padding:28px 16px 40px}.brand{display:flex;align-items:center;gap:12px;margin-bottom:20px}.logo{width:44px;height:44px;border-radius:14px;background:var(--tonal);display:grid;place-items:center}.logo svg{width:24px;height:24px;fill:var(--on-tonal)}.brand b{font-size:15px;letter-spacing:.2px;color:var(--muted);font-weight:500}h1{font-size:28px;line-height:36px;font-weight:600;margin:0 0 8px}.lead{color:var(--muted);margin:0 0 24px}.card{background:var(--surface);border-radius:28px;padding:20px;margin-bottom:12px;box-shadow:0 1px 2px rgba(0,0,0,.08)}@media(prefers-color-scheme:dark){.card{box-shadow:none;background:var(--surface)}}.card h2{font-size:18px;font-weight:600;margin:0 0 4px}.card p{color:var(--muted);margin:0 0 16px;font-size:14px}.btn{display:flex;align-items:center;justify-content:center;gap:8px;width:100%;height:56px;border:0;border-radius:999px;font:600 16px/1 inherit;font-family:inherit;text-decoration:none;cursor:pointer;transition:transform .12s,filter .12s}.btn:active{transform:scale(.98);filter:brightness(.92)}.filled{background:var(--primary);color:var(--on-primary)}.tonal{background:var(--tonal);color:var(--on-tonal)}textarea{width:100%;min-height:140px;resize:vertical;border:1px solid var(--outline);border-radius:16px;background:transparent;color:var(--on);padding:14px 16px;font:14px/1.45 ui-monospace,\"Roboto Mono\",monospace;margin-bottom:12px;outline:none}textarea:focus{border:2px solid var(--primary);padding:13px 15px}.note{display:flex;gap:10px;align-items:flex-start;color:var(--muted);font-size:13px;margin-top:20px;padding:0 4px}.note svg{flex:none;width:18px;height:18px;fill:var(--muted);margin-top:2px}.state{text-align:center;padding:32px 20px}.state .icon{width:72px;height:72px;border-radius:24px;margin:0 auto 16px;display:grid;place-items:center;background:var(--tonal)}.state .icon svg{width:36px;height:36px;fill:var(--on-tonal)}.state.err .icon{background:color-mix(in srgb,var(--error) 18%,transparent)}.state.err .icon svg{fill:var(--error)}[hidden]{display:none!important}</style></head><body><main><div class=\"brand\"><div class=\"logo\"><svg viewBox=\"0 0 24 24\"><path d=\"M12 2 4 5v6c0 5.25 3.4 10.15 8 11.5 4.6-1.35 8-6.25 8-11.5V5l-8-3zm0 2.2 6 2.25V11c0 4.15-2.55 8.1-6 9.4V4.2z\"/></svg></div><b>Veil</b></div><section class=\"card state {{cls}}\"><div class=\"icon\">{{icon}}</div><h1>{{title}}</h1><p class=\"lead\" style=\"margin:0\">{{msg}}</p></section></main></body></html>"

        const val CHECK_ICON = "<svg viewBox=\"0 0 24 24\"><path d=\"M9 16.2 4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4z\"/></svg>"
        const val ERROR_ICON = "<svg viewBox=\"0 0 24 24\"><path d=\"M11 15h2v2h-2zm0-8h2v6h-2zm1-5a10 10 0 1 0 0 20 10 10 0 0 0 0-20z\"/></svg>"

        fun result(ok: Boolean, title: String, msg: String): String = RESULT
            .replace("{{cls}}", if (ok) "" else "err")
            .replace("{{icon}}", if (ok) CHECK_ICON else ERROR_ICON)
            .replace("{{title}}", title)
            .replace("{{msg}}", msg)
    }
}
