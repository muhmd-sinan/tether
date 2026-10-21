package dev.tether

import android.app.Application
import android.content.Context

class TetherApp : Application() {
    lateinit var state: TetherState
        private set

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        state = TetherState(this)
    }
}

val Context.tether: TetherState get() = (applicationContext as TetherApp).state
