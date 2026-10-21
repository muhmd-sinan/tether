package dev.tether

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the process (and so the control channel) alive while the app is in
 * the background, so Claude Code alerts reach the phone. Shows a quiet
 * ongoing notification, as Android requires.
 */
class TetherService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            tether.updateBackgroundAlerts(false)
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifications.ONGOING_ID, Notifications.ongoing(this), type)
        tether.serviceStarted()
        return START_STICKY
    }

    companion object {
        private const val ACTION_TURN_OFF = "dev.tether.TURN_OFF_ALERTS"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TetherService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TetherService::class.java))
        }

        fun turnOffIntent(context: Context) =
            Intent(context, TetherService::class.java).setAction(ACTION_TURN_OFF)
    }
}
