package dev.tether

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.tether.term.Palette
import dev.tether.ui.ConnectScreen
import dev.tether.ui.MainScreen
import dev.tether.ui.TetherTheme

class MainActivity : ComponentActivity() {
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.install()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openFrom(intent)
        val state = tether
        setContent {
            TetherTheme {
                LifecycleStartEffect(state) {
                    state.enterForeground()
                    onStopOrDispose { state.enterBackground() }
                }
                if (state.host == null) ConnectScreen(state) else MainScreen(state)
            }
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** A tapped alert carries the session it is about. */
    private fun openFrom(intent: Intent?) {
        intent?.getStringExtra(Notifications.EXTRA_SESSION)?.let(tether::openSession)
    }
}
