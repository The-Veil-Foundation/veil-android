package dev.local.veil.platform

import android.app.ActivityManager
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun isLowRamDevice(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        manager?.isLowRamDevice == true
    }
}
