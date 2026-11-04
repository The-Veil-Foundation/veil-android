package dev.local.veil.platform

import androidx.compose.ui.graphics.ImageBitmap

/** Установленное приложение — для выбора в раздельном туннелировании. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

interface PackageLister {
    suspend fun installedApps(): List<AppEntry>

    /** Иконка приложения; на платформах без иконок — null (покажем букву). */
    suspend fun icon(packageName: String): ImageBitmap? = null
}
