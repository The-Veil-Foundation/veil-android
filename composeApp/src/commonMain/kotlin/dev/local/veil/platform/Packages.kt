package dev.local.veil.platform

/** Установленное приложение — для выбора в раздельном туннелировании. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
)

interface PackageLister {
    suspend fun installedApps(): List<AppEntry>
}
