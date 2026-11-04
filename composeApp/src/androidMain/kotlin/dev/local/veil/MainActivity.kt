package dev.local.veil

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dev.local.veil.platform.AndroidContainer
import dev.local.veil.ui.App

class MainActivity : ComponentActivity() {

    private val isTelevision: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_TELEVISION

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // На Android TV edge-to-edge даёт лишние системные отступы и иногда
        // оставляет Compose в промежуточном состоянии при старте лаунчера.
        if (!isTelevision) enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = AndroidContainer(this)
        setContent { App(container) }

        // У телевизора нет шторки уведомлений; запрос разрешения только
        // задерживает первый кадр и может выглядеть как зависание запуска.
        if (!isTelevision && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
