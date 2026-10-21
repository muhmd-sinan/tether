package dev.tether

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tether.net.AgentClient
import dev.tether.net.SessionConnection
import dev.tether.net.SessionInfo
import dev.tether.term.StickyKeys
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * App-wide connection state. Lives in [TetherApp] rather than a ViewModel so
 * the control channel can stay open in the background (for Claude alerts)
 * after the activity is gone.
 *
 * Foreground: control channel + every session stream.
 * Background with alerts on: control channel only, kept alive by [TetherService].
 * Background with alerts off: nothing.
 */
class TetherState(private val app: Context) {
    private val prefs = app.getSharedPreferences("tether", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    var host by mutableStateOf(prefs.getString("host", null))
        private set
    var port by mutableStateOf(prefs.getInt("port", 8722))
        private set
    val lastHost: String get() = prefs.getString("lastHost", "") ?: ""
    var fontSize by mutableFloatStateOf(prefs.getFloat("fontSize", 13f))
        private set
    var backgroundAlerts by mutableStateOf(prefs.getBoolean("backgroundAlerts", true))
        private set

    var agent by mutableStateOf<AgentClient?>(null)
        private set
    val sessions = mutableStateListOf<SessionInfo>()
    val connections = mutableStateMapOf<String, SessionConnection>()
    var selected by mutableStateOf<String?>(null)
    val sticky = StickyKeys()

    /** Last grid size a terminal view reported; new sessions start at it. */
    private var grid: Pair<Int, Int>? =
        prefs.getInt("gridCols", 0).takeIf { it > 0 }?.let { it to prefs.getInt("gridRows", 0) }

    var foreground = false
        private set

    init {
        host?.let { buildAgent(it, port) }
    }

    val current: SessionConnection? get() = selected?.let { connections[it] }

    private fun baseUrl(h: String, p: Int) = if (':' in h) "ws://[$h]:$p" else "ws://$h:$p"

    private fun buildAgent(h: String, p: Int) {
        agent = AgentClient(http, baseUrl(h, p), ::reconcile, { id -> selected = id }, ::onAlert)
    }

    fun enterForeground() {
        foreground = true
        Notifications.clearAlerts(app)
        agent?.start()
        connections.values.forEach { it.start() }
        syncService()
    }

    fun enterBackground() {
        foreground = false
        connections.values.forEach { it.stop() }
        if (!keepsRunningInBackground()) agent?.stop()
    }

    /** Called by [TetherService] when it starts (also after the system restarts it). */
    fun serviceStarted() {
        if (keepsRunningInBackground()) agent?.start()
    }

    private fun keepsRunningInBackground() = backgroundAlerts && host != null

    private fun syncService() {
        if (keepsRunningInBackground()) TetherService.start(app) else TetherService.stop(app)
    }

    fun updateBackgroundAlerts(on: Boolean) {
        backgroundAlerts = on
        prefs.edit().putBoolean("backgroundAlerts", on).apply()
        if (!on && !foreground) agent?.stop()
        syncService()
    }

    fun connectTo(newHost: String, newPort: Int) {
        teardown()
        host = newHost
        port = newPort
        prefs.edit().putString("host", newHost).putString("lastHost", newHost).putInt("port", newPort).apply()
        buildAgent(newHost, newPort)
        if (foreground) agent?.start()
        syncService()
    }

    fun forgetHost() {
        teardown()
        host = null
        prefs.edit().remove("host").apply()
        syncService()
    }

    private fun teardown() {
        agent?.stop()
        agent = null
        connections.values.forEach { it.stop() }
        connections.clear()
        sessions.clear()
        selected = null
    }

    private fun reconcile(list: List<SessionInfo>) {
        val url = agent?.baseUrl ?: return
        val ids = list.map { it.id }.toSet()
        (connections.keys - ids).forEach { connections.remove(it)?.stop() }
        for (s in list) {
            if (s.id !in connections) {
                val c = SessionConnection(http, url, s.id)
                connections[s.id] = c
                if (foreground) c.start()
            }
        }
        sessions.clear()
        sessions.addAll(list)
        if (selected !in ids) selected = list.lastOrNull()?.id
    }

    private fun onAlert(id: String, name: String, message: String) {
        if (foreground && selected == id) return  // already looking at it
        Notifications.alert(app, id, name, message)
    }

    /** Opened from a notification: show that session. */
    fun openSession(id: String) {
        selected = id
    }

    fun reportGrid(cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0 || grid == cols to rows) return
        grid = cols to rows
        prefs.edit().putInt("gridCols", cols).putInt("gridRows", rows).apply()
    }

    fun newSession(shell: String) = agent?.newSession(shell, grid)
    fun newClaudeSession(cwd: String) = agent?.newSession("claude", grid, cwd)
    fun requestProjects() = agent?.requestProjects()
    fun closeSession(id: String) = agent?.close(id)
    fun renameSession(id: String, name: String) = agent?.rename(id, name)
    fun retry() = agent?.retryNow()

    fun updateFontSize(sp: Float) {
        fontSize = sp
        prefs.edit().putFloat("fontSize", sp).apply()
    }
}
