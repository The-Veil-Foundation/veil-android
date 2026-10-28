package dev.local.veil.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Обновления из GitHub Releases: раз в проверку берём latest, сравниваем
 * версии, качаем APK и отдаём системе установщик.
 */
class AndroidUpdateService(private val context: Context) : UpdateService {

    private val json = Json { ignoreUnknownKeys = true }

    override val currentVersion: String by lazy {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.0.0"
    }

    override suspend fun latest(): Result<AppRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val body = httpGet("$API/latest")
                ?: return@runCatching null
            val release = json.decodeFromString(ReleaseDto.serializer(), body)
            val version = release.tag.removePrefix("v").removePrefix("V")
            if (!isNewer(version, currentVersion)) return@runCatching null

            val apk = release.assets
                .filter { it.name.endsWith(".apk", ignoreCase = true) }
                .sortedBy { asset ->
                    when {
                        asset.name.contains("universal", ignoreCase = true) -> 0
                        asset.name.contains("arm64", ignoreCase = true) -> 1
                        asset.name.contains("x86_64", ignoreCase = true) -> 2
                        else -> 3
                    }
                }
                .firstOrNull() ?: return@runCatching null

            AppRelease(
                version = version,
                notes = release.body.orEmpty().trim(),
                downloadUrl = apk.url,
                pageUrl = release.pageUrl,
            )
        }
    }

    override suspend fun download(
        release: AppRelease,
        onProgress: (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "veil-${release.version}.apk")
            if (target.exists()) target.delete()

            val connection = (URL(release.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Veil-Android")
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("GitHub ответил HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                var copied = 0L
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                onProgress(1f)
                target.absolutePath
            } finally {
                connection.disconnect()
            }
        }
    }

    override fun install(path: String): Result<Unit> = runCatching {
        val file = File(path)
        require(file.exists()) { "Файл обновления не найден" }

        // Android 8+: установка из неизвестного источника включается отдельно.
        if (!context.packageManager.canRequestPackageInstalls()) {
            val settings = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settings)
            error("Разреши установку из этого источника и нажми «Обновить» ещё раз")
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun openSource() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun httpGet(url: String): String? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Veil-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            when (connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                // Репозиторий есть, но релизов ещё не публиковали.
                404 -> error("Релизов на GitHub пока нет")
                else -> error("GitHub ответил HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Сравнение версий по числам: 0.10.0 новее 0.9.9. */
    private fun isNewer(candidate: String, current: String): Boolean {
        fun parts(value: String) = value.split('-', '+').first()
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (index in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        @SerialName("html_url") val pageUrl: String,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        @SerialName("browser_download_url") val url: String,
    )

    private companion object {
        const val API = "https://api.github.com/repos/s4ntiksu/veil-android/releases"
        const val SOURCE_URL = "https://github.com/s4ntiksu/veil-android"
    }
}
