package dev.local.veil.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

object AppLog {
    private val lines = MutableStateFlow(listOf("[info] Application session started"))
    fun event(name: String) { lines.update { (it + "[info] $name").takeLast(500) } }
    fun read(): String = lines.value.joinToString("\n")
    fun clear() { lines.value = emptyList() }
}
