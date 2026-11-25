package dev.local.veil.core

import dev.local.veil.ui.translations.Translations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TranslationsTest {
    @Test
    fun allAdditionalLanguagesHaveTranslationsAndSafeFallback() {
        for (language in AppLanguage.entries.filter { it !in listOf(AppLanguage.SYSTEM, AppLanguage.RU, AppLanguage.EN) }) {
            assertNotEquals("Alive only", Translations.translate("Alive only", language), language.name)
            assertEquals("Android-only string", Translations.translate("Android-only string", language))
        }
    }

    @Test
    fun detectsLanguageRegionsAndUnknownLocales() {
        assertEquals(AppLanguage.ZH, AppLanguage.fromCode("zh-CN"))
        assertEquals(AppLanguage.PT, AppLanguage.fromCode("pt_BR"))
        assertEquals(AppLanguage.AR, AppLanguage.fromCode("ar"))
        assertEquals(AppLanguage.EN, AppLanguage.fromCode("unknown"))
    }
}
