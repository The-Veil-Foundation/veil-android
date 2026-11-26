package dev.local.veil.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ApkArchitectureTest {
    private val assets = listOf(
        "veil-0.1.1-arm64-v8a.apk",
        "veil-0.1.1-x86_64.apk",
        "veil-0.1.1-armeabi-v7a.apk",
    )

    @Test fun armv7NeverSelectsArm64() {
        assertEquals(2, compatibleApkIndex(assets, listOf("armeabi-v7a", "armeabi")))
        assertNull(compatibleApkIndex(assets.take(2), listOf("armeabi-v7a")))
    }

    @Test fun respectsDevicePreference() {
        assertEquals(0, compatibleApkIndex(assets, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(1, compatibleApkIndex(assets, listOf("x86_64", "x86")))
    }

    @Test fun fallsBackOnlyToExplicitUniversalApk() {
        assertEquals(1, compatibleApkIndex(
            listOf("veil.apk", "veil-0.1.1-universal.apk"), listOf("armeabi-v7a"),
        ))
        assertNull(compatibleApkIndex(listOf("veil.apk"), listOf("armeabi-v7a")))
    }
}
