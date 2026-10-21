package dev.tether

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val ALERTS = "alerts"
    private const val ONGOING = "background"
    const val ONGOING_ID = 1
    const val EXTRA_SESSION = "session"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(ALERTS, "Claude Code alerts",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When Claude finishes or needs your input"
        })
        nm.createNotificationChannel(NotificationChannel(ONGOING, "Background connection",
            NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shown while Tether stays connected for alerts"
            setShowBadge(false)
        })
    }

    private fun openApp(context: Context, session: String?, code: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        session?.let { intent.putExtra(EXTRA_SESSION, it) }
        return PendingIntent.getActivity(context, code, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ongoing(context: Context) = NotificationCompat.Builder(context, ONGOING)
        .setSmallIcon(R.drawable.ic_stat_tether)
        .setContentTitle("Connected for alerts")
        .setContentText("Tether tells you when Claude needs you.")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setContentIntent(openApp(context, null, 0))
        .addAction(0, "Turn off", PendingIntent.getService(context, 1, TetherService.turnOffIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        .build()

    /** One notification per session; a newer alert replaces the older one. */
    fun alert(context: Context, id: String, name: String, message: String) {
        val n = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(R.drawable.ic_stat_tether)
            .setContentTitle(name.ifBlank { "Claude Code" })
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, id, id.hashCode()))
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, ALERT_ID, n)
        } catch (_: SecurityException) {
            // Notification permission not granted; nothing to show.
        }
    }

    fun clearAlerts(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.activeNotifications.filter { it.id == ALERT_ID }.forEach { nm.cancel(it.tag, it.id) }
    }

    private const val ALERT_ID = 2
}
