package dev.local.veil.platform

import androidx.compose.ui.graphics.ImageBitmap

data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

interface PackageLister {
    suspend fun installedApps(): List<AppEntry>

    suspend fun icon(packageName: String): ImageBitmap? = null
}
