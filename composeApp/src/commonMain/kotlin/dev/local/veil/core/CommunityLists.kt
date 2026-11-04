package dev.local.veil.core

/**
 * Готовый список доменов и подсетей из проекта `itdoginfo/allow-domains`.
 * Пользователь выбирает «Telegram» или «Россия внутри» вместо того, чтобы
 * вставлять тысячу доменов в правило: список скачивается и кэшируется сам.
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
        // Регионы
        CommunityList("ru-inside", "Russia inside", "Россия внутри", CommunityCategory.REGION, "Russia/inside-raw.lst"),
        CommunityList("ru-outside", "Russia outside", "Россия снаружи", CommunityCategory.REGION, "Russia/outside-raw.lst"),
        CommunityList("ua-inside", "Ukraine", "Украина", CommunityCategory.REGION, "Ukraine/inside-raw.lst"),

        // Категории
        CommunityList("geoblock", "Geo Block", "Геоблок", CommunityCategory.CONTENT, "Categories/geoblock.lst"),
        CommunityList("block", "Block", "Блок", CommunityCategory.CONTENT, "Categories/block.lst"),
        CommunityList("news", "News", "Новости", CommunityCategory.CONTENT, "Categories/news.lst"),
        CommunityList("anime", "Anime", "Аниме", CommunityCategory.CONTENT, "Categories/anime.lst"),
        CommunityList("porn", "Porn", "18+", CommunityCategory.CONTENT, "Categories/porn.lst"),
        CommunityList("hodca", "H.O.D.C.A", "H.O.D.C.A", CommunityCategory.CONTENT, "Categories/hodca.lst"),

        // Сервисы
        CommunityList("youtube", "YouTube", "YouTube", CommunityCategory.SERVICE, "Services/youtube.lst"),
        CommunityList(
            "telegram", "Telegram", "Telegram", CommunityCategory.SERVICE,
            "Services/telegram.lst", "Subnets/IPv4/telegram.lst", "Subnets/IPv6/telegram.lst",
        ),
        CommunityList(
            "discord", "Discord", "Discord", CommunityCategory.SERVICE,
            "Services/discord.lst", "Subnets/IPv4/discord.lst", "Subnets/IPv6/discord.lst",
        ),
        CommunityList(
            "meta", "Meta", "Meta", CommunityCategory.SERVICE,
            "Services/meta.lst", "Subnets/IPv4/meta.lst", "Subnets/IPv6/meta.lst",
        ),
        CommunityList(
            "twitter", "Twitter (X)", "Twitter (X)", CommunityCategory.SERVICE,
            "Services/twitter.lst", "Subnets/IPv4/twitter.lst", "Subnets/IPv6/twitter.lst",
        ),
        CommunityList("tiktok", "TikTok", "TikTok", CommunityCategory.SERVICE, "Services/tiktok.lst"),
        CommunityList("hdrezka", "HDRezka", "HDRezka", CommunityCategory.SERVICE, "Services/hdrezka.lst"),
        CommunityList(
            "roblox", "Roblox", "Roblox", CommunityCategory.SERVICE,
            "Services/roblox.lst", "Subnets/IPv4/roblox.lst",
        ),
        CommunityList("google-ai", "Google AI", "Google AI", CommunityCategory.SERVICE, "Services/google_ai.lst"),
        CommunityList("google-play", "Google Play", "Google Play", CommunityCategory.SERVICE, "Services/google_play.lst"),
        CommunityList(
            "google-meet", "Google Meet", "Google Meet", CommunityCategory.SERVICE,
            "Services/google_meet.lst", "Subnets/IPv4/google_meet.lst",
        ),
        CommunityList(
            "cloudflare", "Cloudflare", "Cloudflare", CommunityCategory.SERVICE,
            "Services/cloudflare.lst", "Subnets/IPv4/cloudflare.lst", "Subnets/IPv6/cloudflare.lst",
        ),
        CommunityList(
            "cloudfront", "CloudFront", "CloudFront", CommunityCategory.SERVICE,
            "Services/cloudfront.lst", "Subnets/IPv4/cloudfront.lst",
        ),
        CommunityList(
            "digitalocean", "DigitalOcean", "DigitalOcean", CommunityCategory.SERVICE,
            "Services/digitalocean.lst", "Subnets/IPv4/digitalocean.lst",
        ),
        CommunityList(
            "hetzner", "Hetzner", "Hetzner", CommunityCategory.SERVICE,
            "Services/hetzner.lst", "Subnets/IPv4/hetzner.lst",
        ),
        CommunityList(
            "ovh", "OVH", "OVH", CommunityCategory.SERVICE,
            "Services/ovh.lst", "Subnets/IPv4/ovh.lst",
        ),
    )

    fun list(id: String): CommunityList? = all.firstOrNull { it.id == id }

    fun inCategory(category: CommunityCategory): List<CommunityList> =
        all.filter { it.category == category }
}
