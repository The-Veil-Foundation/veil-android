package dev.local.veil.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Links that arrive from outside the app, such as `veil://pair?u=…` opened
 * from the TV's pairing page. The platform puts a link here; the UI takes it
 * once and handles it like text added by hand.
 */
object IncomingLinks {
    private val pending = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> get() = pending

    fun offer(text: String) {
        pending.value = text
    }

    /** Returns the waiting link and clears it, so a recomposition does not handle it twice. */
    fun take(): String? = pending.value.also { pending.value = null }
}
