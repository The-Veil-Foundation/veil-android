package dev.local.veil.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object IncomingLinks {
    private val pending = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> get() = pending

    fun offer(text: String) {
        pending.value = text
    }

    fun take(): String? = pending.value.also { pending.value = null }
}
