package dev.local.veil.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import dev.local.veil.core.AppLanguage

/**
 * Текущий язык интерфейса. `SYSTEM` разрешается один раз при старте в
 * конкретный язык, поэтому внутри приложения здесь всегда `RU` или `EN`.
 */
val LocalLanguage = staticCompositionLocalOf { AppLanguage.RU }

/**
 * Глобальное зеркало языка для строк, которые живут вне композиции:
 * форматтеров байт, подписей перечислений и буллетинов состояния.
 */
object AppLocale {
    var language: AppLanguage = AppLanguage.RU
}

/** Выбирает русскую или английскую строку под текущий язык. */
fun tr(ru: String, en: String): String =
    if (AppLocale.language == AppLanguage.EN) en else ru

/** Публикует выбранный язык для композиции и для [tr]. */
@Composable
fun ProvideLanguage(language: AppLanguage, content: @Composable () -> Unit) {
    AppLocale.language = language
    CompositionLocalProvider(LocalLanguage provides language, content = content)
}

/** Язык системы, если пользователь не выбрал конкретный. */
expect fun systemLanguage(): AppLanguage

/** Подписи способа замера задержки. */
fun pingModeTitle(mode: dev.local.veil.platform.PingMode): String = when (mode) {
    dev.local.veil.platform.PingMode.REAL -> tr("Реальный", "Real")
    else -> mode.label
}

fun pingModeHint(mode: dev.local.veil.platform.PingMode): String = when (mode) {
    dev.local.veil.platform.PingMode.TCP ->
        tr(
            "Только подключение к порту сервера: быстро, но цифры заниженные. Узлы на UDP не отвечают.",
            "Port connection only: fast, but the numbers are optimistic. UDP servers do not respond.",
        )
    dev.local.veil.platform.PingMode.ICMP ->
        tr(
            "Как команда ping. Многие серверы не отвечают на ICMP — тогда будет прочерк.",
            "Like the ping command. Many servers ignore ICMP, so you may see a dash.",
        )
    dev.local.veil.platform.PingMode.REAL ->
        tr(
            "Запрос через сервер в интернет: то, что вы почувствуете в приложениях. Идёт по очереди, дольше.",
            "A request through the server to the internet: what you actually feel in apps. Runs sequentially and takes longer.",
        )
}

/** Подписи пресетов маршрутизации — тексты macOS (RoutingPreset.title/subtitle). */
fun routingPresetTitle(preset: dev.local.veil.core.RoutingPreset): String = when (preset) {
    dev.local.veil.core.RoutingPreset.GLOBAL -> tr("Всё через прокси", "Global")
    dev.local.veil.core.RoutingPreset.BYPASS_LAN -> tr("Кроме локальной сети", "Bypass LAN")
    dev.local.veil.core.RoutingPreset.BYPASS_CHINA -> tr("Китай напрямую", "Bypass China")
    dev.local.veil.core.RoutingPreset.BYPASS_RU -> tr("Россия напрямую", "Bypass Russia")
    dev.local.veil.core.RoutingPreset.CUSTOM -> tr("Свои правила", "Custom")
}

fun routingPresetSubtitle(preset: dev.local.veil.core.RoutingPreset): String = when (preset) {
    dev.local.veil.core.RoutingPreset.GLOBAL -> tr("Весь трафик через прокси.", "All traffic through the proxy.")
    dev.local.veil.core.RoutingPreset.BYPASS_LAN ->
        tr("Всё через прокси, кроме локальных адресов.", "Proxy everything except local/LAN addresses.")
    dev.local.veil.core.RoutingPreset.BYPASS_CHINA ->
        tr("Сайты Китая и LAN напрямую, остальное через прокси.", "Mainland China sites & LAN go direct, rest via proxy.")
    dev.local.veil.core.RoutingPreset.BYPASS_RU ->
        tr("Российские и госсайты .ru напрямую, остальное через прокси.", "Russian & .ru-gov sites go direct, rest via proxy.")
    dev.local.veil.core.RoutingPreset.CUSTOM -> tr("Свой упорядоченный список правил.", "Your own ordered rule list.")
}

fun groupKindTitle(kind: dev.local.veil.core.GroupKind): String =
    if (kind == dev.local.veil.core.GroupKind.SELECTOR) {
        tr("Ручной выбор", "Manual selection")
    } else {
        tr("Самый быстрый", "Fastest")
    }

fun groupKindSubtitle(kind: dev.local.veil.core.GroupKind): String =
    if (kind == dev.local.veil.core.GroupKind.SELECTOR) {
        tr("Всегда выбранный участник", "Always the selected member")
    } else {
        tr("Замер и выбор быстрейшего", "Measures and picks the fastest")
    }

fun ruleTargetTitle(target: dev.local.veil.core.RuleTarget): String = when (target.kind) {
    dev.local.veil.core.TargetKind.PROXY -> tr("Прокси", "Proxy")
    dev.local.veil.core.TargetKind.DIRECT -> tr("Напрямую", "Direct")
    dev.local.veil.core.TargetKind.BLOCK -> tr("Блокировать", "Block")
    dev.local.veil.core.TargetKind.SERVER -> tr("Узел", "Server")
    dev.local.veil.core.TargetKind.GROUP -> tr("Группа", "Group")
}

fun dnsKindTitle(kind: dev.local.veil.core.DnsServerKind): String =
    if (kind == dev.local.veil.core.DnsServerKind.LOCAL) tr("Системный", "System") else kind.title

/** Подписи темы оформления. */
fun appearanceTitle(appearance: dev.local.veil.core.Appearance): String = when (appearance) {
    dev.local.veil.core.Appearance.SYSTEM -> tr("Системная", "System")
    dev.local.veil.core.Appearance.LIGHT -> tr("Светлая", "Light")
    dev.local.veil.core.Appearance.DARK -> tr("Тёмная", "Dark")
}
