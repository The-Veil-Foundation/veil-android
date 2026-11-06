package dev.local.veil.platform

import dev.local.veil.core.GeoAssetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает geoip.dat и geosite.dat в каталог ядра.
 *
 * Xray без этих файлов не может разобрать правила `geosite:` и `geoip:`,
 * поэтому они качаются отдельно от APK: файлы весят десятки мегабайт и
 * обновляются чаще, чем приложение.
 */
class AndroidGeoService(private val dir: File) : GeoService {

    override val directory: String get() = dir.absolutePath

    override val hasAssets: Boolean
        get() = listOf("geoip.dat", "geosite.dat").all { name ->
            File(dir, name).let { it.exists() && it.length() > 0 }
        }

    override suspend fun download(
        source: GeoAssetSource,
        customGeoip: String,
        customGeosite: String,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val geoip = source.geoipUrl(customGeoip)
                val geosite = source.geositeUrl(customGeosite)
                require(geoip.startsWith("https://") && geosite.startsWith("https://")) {
                    "Укажите https-адреса geoip.dat и geosite.dat"
                }
                dir.mkdirs()
                downloadTo(geoip, File(dir, "geoip.dat"))
                downloadTo(geosite, File(dir, "geosite.dat"))
                check(hasAssets) { "Файлы базы не скачались" }
            }
        }

    private fun downloadTo(url: String, target: File) {
        var current = URL(url)
        var redirects = 0
        while (redirects < 6) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", dev.local.veil.AppVersion.USER_AGENT)
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: error("редирект без Location: $url")
                current = URL(current, location)
                connection.disconnect()
                redirects++
                continue
            }
            if (code !in 200..299) {
                connection.disconnect()
                error("HTTP $code: $url")
            }
            val temp = File(target.parentFile, target.name + ".part")
            connection.inputStream.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return
        }
        error("слишком много редиректов: $url")
    }
}
