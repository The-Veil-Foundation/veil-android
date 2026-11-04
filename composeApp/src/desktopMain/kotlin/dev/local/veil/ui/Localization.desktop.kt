package dev.local.veil.ui

import dev.local.veil.core.AppLanguage
import java.util.Locale

actual fun systemLanguage(): AppLanguage =
    if (Locale.getDefault().language.equals("ru", ignoreCase = true)) {
        AppLanguage.RU
    } else {
        AppLanguage.EN
    }
