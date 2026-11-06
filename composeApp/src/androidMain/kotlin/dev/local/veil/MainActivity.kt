package dev.local.veil

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import dev.local.veil.core.IncomingLinks
import dev.local.veil.core.Pairing
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
        acceptLink(intent)
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptLink(intent)
    }

    /**
     * veil://pair?u=<адрес приёма>: телефон открыл страницу приставки и нажал
     * «Открыть в Veil». Берём только настоящий адрес приёма в локальной сети,
     * дальше приложение само спросит, что отправить.
     */
    private fun acceptLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW || data.scheme != "veil" || data.host != "pair") return
        val address = data.getQueryParameter("u")?.let { Pairing.parse(it) } ?: return
        IncomingLinks.offer(address)
    }
}
