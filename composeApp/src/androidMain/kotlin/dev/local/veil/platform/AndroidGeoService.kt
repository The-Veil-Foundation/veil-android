package dev.local.veil.platform

import dev.local.veil.core.GeoAssetSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AndroidGeoService(private val dir: File) : GeoService {
    private val mutex = Mutex()
    override val directory: String get() = dir.absolutePath
    override val hasAssets: Boolean get() = listOf("geoip.dat", "geosite.dat").all { File(dir, it).length() > 0 }

    override suspend fun download(source: GeoAssetSource, customGeoip: String, customGeosite: String) =
        downloadWithProgress(source, customGeoip, customGeosite) {}

    override suspend fun downloadWithProgress(source: GeoAssetSource, customGeoip: String, customGeosite: String,
        onProgress: (GeoDownloadProgress) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val staging = File(dir, "geo-download")
            try {
                check(staging.mkdirs() || staging.isDirectory)
                // Keep the installed pair intact until BOTH downloads have completed.
                downloadTo(source.geoipUrl(customGeoip), File(staging, "geoip.dat"), onProgress)
                downloadTo(source.geositeUrl(customGeosite), File(staging, "geosite.dat"), onProgress)
                currentCoroutineContext().ensureActive()
                val names = listOf("geoip.dat", "geosite.dat")
                val installed = mutableListOf<String>()
                names.forEach { name ->
                    val old = File(dir, name)
                    if (old.exists()) old.copyTo(File(staging, "$name.backup"), overwrite = true)
                }
                try {
                    names.forEach { name ->
                        check(File(staging, name).renameTo(File(dir, name))) { "Не удалось установить $name" }
                        installed += name
                    }
                } catch (error: Exception) {
                    installed.forEach { name ->
                        val backup = File(staging, "$name.backup")
                        if (backup.exists()) backup.copyTo(File(dir, name), overwrite = true) else File(dir, name).delete()
                    }
                    throw error
                }
                Result.success(Unit)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Result.failure(error)
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    private suspend fun downloadTo(url: String, target: File, progress: (GeoDownloadProgress) -> Unit) {
        var current = URL(url)
        repeat(6) {
            currentCoroutineContext().ensureActive()
            require(current.protocol == "https") { "Для геобаз нужен HTTPS" }
            val connection = current.openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", dev.local.veil.AppVersion.USER_AGENT)
                connection.setRequestProperty("Accept-Encoding", "identity")
                val code = connection.responseCode
                if (code in listOf(301, 302, 303, 307, 308)) {
                    current = URL(current, connection.getHeaderField("Location") ?: error("Редирект без адреса"))
                    return@repeat
                }
                check(code == 200) { "${target.name}: HTTP $code" }
                val total = connection.contentLengthLong.takeIf { it > 0 }
                require(total == null || total <= MAX_BYTES) { "Слишком большая геобаза" }
                var bytes = 0L
                var reportedAt = 0L
                progress(GeoDownloadProgress(target.name, 0, total))
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            bytes += count
                            check(bytes <= MAX_BYTES) { "Слишком большая геобаза" }
                            output.write(buffer, 0, count)
                            val now = System.nanoTime()
                            if (now - reportedAt >= 150_000_000L) {
                                progress(GeoDownloadProgress(target.name, bytes, total))
                                reportedAt = now
                            }
                        }
                    }
                }
                check(bytes > 0 && (total == null || bytes == total)) { "${target.name}: загрузка оборвалась" }
                validateGeoFile(target)
                progress(GeoDownloadProgress(target.name, bytes, total))
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Слишком много перенаправлений")
    }

    companion object { private const val MAX_BYTES = 128L * 1024 * 1024 }
}

/** GeoIPList/GeoSiteList contain repeated length-delimited field 1 entries. */
internal fun validateGeoFile(file: File) {
    java.io.RandomAccessFile(file, "r").use { input ->
        check(input.length() > 0) { "Пустая геобаза" }
        while (input.filePointer < input.length()) {
            check(input.read() == 10) { "${file.name}: неверный формат геобазы" }
            var length = 0L
            var shift = 0
            while (true) {
                val byte = input.read()
                check(byte >= 0 && shift <= 28) { "Повреждённая геобаза" }
                length = length or ((byte and 127).toLong() shl shift)
                if (byte and 128 == 0) break
                shift += 7
            }
            check(length > 0 && length <= input.length() - input.filePointer) { "Геобаза загружена не полностью" }
            input.seek(input.filePointer + length)
        }
    }
}
