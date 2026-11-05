package dev.local.veil.core

/**
 * Готовый список доменов и подсетей из проекта `itdoginfo/allow-domains`.
 * Пользователь включает «Россия внутри» вместо того, чтобы вставлять тысячу
 * доменов в правило: список скачивается и кэшируется сам.
 */
data class CommunityList(
    val id: String,
    val title: String,
    val titleRu: String,
    val category: CommunityCategory,
    val domainPath: String,
    val ipv4Path: String? = null,
    val ipv6Path: String? = null,
) {
    /** Заголовок под текущий язык. */
    fun title(language: AppLanguage): String =
        if (language == AppLanguage.EN) title else titleRu
}

enum class CommunityCategory {
    REGION,
    CONTENT,
    SERVICE,
}

/** Каталог списков в порядке отображения. */
object CommunityCatalog {
    const val REPOSITORY = "itdoginfo/allow-domains"
    const val BRANCH = "main"
    const val HOMEPAGE = "https://github.com/itdoginfo/allow-domains"

    fun rawUrl(path: String): String =
        "https://raw.githubusercontent.com/$REPOSITORY/$BRANCH/$path"

    val all: List<CommunityList> = listOf(
        // Российские сайты и подсети, которые должны идти напрямую.
        CommunityList("ru-inside", "Russia inside", "Россия внутри", CommunityCategory.REGION, "Russia/inside-raw.lst"),
    )

    fun list(id: String): CommunityList? = all.firstOrNull { it.id == id }

    fun inCategory(category: CommunityCategory): List<CommunityList> =
        all.filter { it.category == category }
}
