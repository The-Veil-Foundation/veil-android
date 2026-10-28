package dev.local.veil

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.local.veil.platform.DesktopContainer
import dev.local.veil.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Veil",
        state = rememberWindowState(width = 420.dp, height = 900.dp),
    ) {
        App(DesktopContainer())
    }
}
