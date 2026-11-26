package dev.local.veil.core

fun compatibleApkIndex(names: List<String>, supportedAbis: List<String>): Int? {
    for (abi in supportedAbis) {
        val index = names.indexOfFirst { it.endsWith("-$abi.apk", ignoreCase = true) }
        if (index >= 0) return index
    }
    return names.indexOfFirst { it.endsWith("-universal.apk", ignoreCase = true) }
        .takeIf { it >= 0 }
}
