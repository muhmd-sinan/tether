package dev.tether

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import dev.tether.net.AgentClient
import dev.tether.net.SessionConnection
import dev.tether.net.SessionInfo
import dev.tether.term.StickyKeys
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TetherViewModel(app: Application) : AndroidViewModel(app) {
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

    var agent by mutableStateOf<AgentClient?>(null)
        private set
    val sessions = mutableStateListOf<SessionInfo>()
    val connections = mutableStateMapOf<String, SessionConnection>()
    var selected by mutableStateOf<String?>(null)
    val sticky = StickyKeys()

    private var started = false

    init {
        host?.let { buildAgent(it, port) }
    }

    val current: SessionConnection? get() = selected?.let { connections[it] }

    private fun baseUrl(h: String, p: Int) = if (':' in h) "ws://[$h]:$p" else "ws://$h:$p"

    private fun buildAgent(h: String, p: Int) {
        val url = baseUrl(h, p)
        agent = AgentClient(http, url, ::reconcile) { id -> selected = id }
    }

    fun start() {
        started = true
        agent?.start()
        connections.values.forEach { it.start() }
    }

    fun stop() {
        started = false
        agent?.stop()
        connections.values.forEach { it.stop() }
    }

    fun connectTo(newHost: String, newPort: Int) {
        teardown()
        host = newHost
        port = newPort
        prefs.edit().putString("host", newHost).putString("lastHost", newHost).putInt("port", newPort).apply()
        buildAgent(newHost, newPort)
        if (started) agent?.start()
    }

    fun forgetHost() {
        teardown()
        host = null
        prefs.edit().remove("host").apply()
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
                if (started) c.start()
            }
        }
        sessions.clear()
        sessions.addAll(list)
        if (selected !in ids) selected = list.lastOrNull()?.id
    }

    fun newSession(shell: String) = agent?.newSession(shell)
    fun closeSession(id: String) = agent?.close(id)
    fun renameSession(id: String, name: String) = agent?.rename(id, name)
    fun retry() = agent?.retryNow()

    fun updateFontSize(sp: Float) {
        fontSize = sp
        prefs.edit().putFloat("fontSize", sp).apply()
    }

    override fun onCleared() {
        stop()
        http.dispatcher.executorService.shutdown()
    }
}
