package dev.local.veil.ui

import androidx.compose.runtime.Composable

/** Подгоняет цвет иконок системных панелей под выбранную тему приложения. */
@Composable
expect fun SystemBarsAppearance(darkTheme: Boolean)
