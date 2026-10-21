package dev.tether

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.compose.LifecycleStartEffect
import dev.tether.term.Palette
import dev.tether.ui.ConnectScreen
import dev.tether.ui.MainScreen
import dev.tether.ui.TetherTheme

class MainActivity : ComponentActivity() {
    private val vm: TetherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        Palette.install()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            TetherTheme {
                LifecycleStartEffect(vm) {
                    vm.start()
                    onStopOrDispose { vm.stop() }
                }
                if (vm.host == null) ConnectScreen(vm) else MainScreen(vm)
            }
        }
    }
}
