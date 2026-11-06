package dev.local.veil.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.local.veil.MainActivity
import dev.local.veil.R
import dev.local.veil.ui.tr

internal object VpnNotifications {
    private const val CHANNEL_ID = "veil_vpn"
    const val ID = 41

    fun build(context: Context, status: VpnStatus, liveUpdates: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "VPN", NotificationManager.IMPORTANCE_LOW).apply {
                    description = tr("Состояние защищённого соединения", "VPN connection status")
                    setShowBadge(false)
                },
            )
        }

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) },
            flags,
        )
        fun serviceAction(requestCode: Int, action: String) = PendingIntent.getService(
            context, requestCode,
            Intent(context, VeilVpnService::class.java).setAction(action),
            flags,
        )

        val title = when (status.phase) {
            VpnPhase.CONNECTED -> tr("VPN подключён", "VPN connected")
            VpnPhase.CONNECTING -> tr("Подключение VPN…", "Connecting VPN…")
            VpnPhase.PAUSED -> tr("VPN на паузе", "VPN paused")
            VpnPhase.ERROR -> tr("Ошибка VPN", "VPN error")
            VpnPhase.DISCONNECTED -> tr("VPN отключён", "VPN disconnected")
        }
        val description = when (status.phase) {
            VpnPhase.PAUSED -> tr("Трафик идёт напрямую", "Traffic is not using the VPN")
            VpnPhase.ERROR -> status.message ?: tr("Не удалось подключиться", "Could not connect")
            else -> status.serverName.ifBlank { tr("Защищённое соединение", "Secure connection") }
        }
        val active = status.phase == VpnPhase.CONNECTED
        val paused = status.phase == VpnPhase.PAUSED
        val ongoing = status.phase in setOf(VpnPhase.CONNECTING, VpnPhase.CONNECTED, VpnPhase.PAUSED)
        val timer = active && liveUpdates && status.connectedSince > 0

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_vpn)
            .setContentTitle(title)
            .setContentText(description)
            .setStyle(NotificationCompat.BigTextStyle().bigText(description))
            .setContentIntent(open)
            .setDeleteIntent(serviceAction(3, VeilVpnService.ACTION_DISMISS_NOTIFICATION))
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(timer)
            .setUsesChronometer(timer)
            .setRequestPromotedOngoing(Build.VERSION.SDK_INT >= 36 && (active || paused) && liveUpdates)

        if (paused && liveUpdates) builder.setShortCriticalText(tr("Пауза", "Paused"))

        // SystemUI renders the timer in both the notification and the Android 16 chip.
        // No notification polling, fake progress, or hard-coded OEM extras are needed.
        if (timer) builder.setWhen(status.connectedSince)
        if (active || status.phase == VpnPhase.PAUSED) {
            builder.addAction(
                0,
                if (status.phase == VpnPhase.PAUSED) tr("Возобновить", "Resume") else tr("Пауза", "Pause"),
                serviceAction(2, VeilVpnService.ACTION_PAUSE),
            )
        }
        if (ongoing) {
            builder.addAction(0, tr("Отключить", "Disconnect"), serviceAction(1, VeilVpnService.ACTION_STOP))
        }
        return builder.build()
    }
}
