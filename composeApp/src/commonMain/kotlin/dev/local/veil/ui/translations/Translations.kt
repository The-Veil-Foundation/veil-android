package dev.local.veil.ui.translations

import dev.local.veil.core.AppLanguage

object Translations {
    private val tables by lazy {
        mapOf(
            AppLanguage.AR to lazy { translationsAR() }, AppLanguage.DE to lazy { translationsDE() },
            AppLanguage.ES to lazy { translationsES() }, AppLanguage.FR to lazy { translationsFR() },
            AppLanguage.HI to lazy { translationsHI() }, AppLanguage.ID to lazy { translationsID() },
            AppLanguage.JA to lazy { translationsJA() }, AppLanguage.PT to lazy { translationsPT() },
            AppLanguage.TR to lazy { translationsTR() }, AppLanguage.ZH to lazy { translationsZH() },
        )
    }
    fun translate(english: String, language: AppLanguage): String =
        if (language == AppLanguage.EN || language == AppLanguage.RU || language == AppLanguage.SYSTEM) english
        else tables[language]?.value?.get(english) ?: english
}
