package dev.local.veil.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** На десктопе динамической палитры из обоев нет — используем резервную. */
@Composable
actual fun dynamicColorScheme(darkTheme: Boolean): ColorScheme? = null
