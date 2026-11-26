package dev.local.veil.platform

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SharingMode { OFF, PROXY, SHIZUKU }

data class SharingStatus(
    val mode: SharingMode = SharingMode.OFF,
    val busy: Boolean = false,
    val active: Boolean = false,
    val message: String = "",
    val host: String = "",
    val port: Int = 10880,
    val username: String = "veil",
    val password: String = "",
)

data class ShizukuStatus(val running: Boolean = false, val authorized: Boolean = false, val root: Boolean = false)

interface InternetSharing {
    val shizuku: StateFlow<ShizukuStatus>
    fun refreshShizuku()
    val vpnConnected: StateFlow<Boolean>
    val status: StateFlow<SharingStatus>
    val shizukuSupported: Boolean
    fun addresses(): List<String>
    fun openShizuku()
    fun authorizeShizuku()
    fun startProxy(address: String)
    fun startShizuku()
    fun stop()
    fun openHotspotSettings()
}
