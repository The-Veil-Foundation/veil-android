package dev.local.veil.ui

import dev.local.veil.core.AppLanguage
import java.util.Locale

actual fun systemLanguage(): AppLanguage =
    AppLanguage.fromCode(Locale.getDefault().language)
