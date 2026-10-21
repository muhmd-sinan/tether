package dev.tether.net

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

data class SessionInfo(val id: String, val name: String, val shell: String, val pcViewers: Int)

/** A folder Claude Code has been used in on the PC. */
data class Project(val path: String, val name: String)

/** Control channel: the live session list, new/close/rename, projects and alerts. */
class AgentClient(
    private val http: OkHttpClient,
    val baseUrl: String,
    private val onSessions: (List<SessionInfo>) -> Unit,
    private val onCreated: (String) -> Unit,
    private val onAlert: (id: String, name: String, message: String) -> Unit,
) {
    enum class Link { Connecting, Online, Offline }

    var link by mutableStateOf(Link.Connecting)
        private set
    var error by mutableStateOf<String?>(null)
    /** Recent Claude Code folders; null until the PC answers a [requestProjects]. */
    var projects by mutableStateOf<List<Project>?>(null)
        private set

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var running = false
    private var retries = 0
    private val reconnect = Runnable { if (running && ws == null) open() }

    fun start() {
        if (running) return
        running = true
        open()
    }

    fun stop() {
        running = false
        main.removeCallbacks(reconnect)
        ws?.close(1000, null)
        ws = null
    }

    fun retryNow() {
        if (!running || ws != null) return
        main.removeCallbacks(reconnect)
        retries = 0
        open()
    }

    fun newSession(shell: String, size: Pair<Int, Int>?, cwd: String? = null) {
        val msg = JSONObject().put("type", "new").put("shell", shell)
        size?.let { msg.put("cols", it.first).put("rows", it.second) }
        cwd?.let { msg.put("cwd", it) }
        send(msg)
    }

    fun requestProjects() {
        projects = null
        send(JSONObject().put("type", "projects"))
    }
    fun close(id: String) = send(JSONObject().put("type", "close").put("id", id))
    fun rename(id: String, name: String) = send(JSONObject().put("type", "rename").put("id", id).put("name", name))

    private fun send(msg: JSONObject) {
        if (ws?.send(msg.toString()) != true) error = "Not connected to the PC"
    }

    private fun open() {
        if (link != Link.Offline) link = Link.Connecting
        lateinit var socket: WebSocket
        socket = http.newWebSocket(Request.Builder().url("$baseUrl/ctl").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { if (ws === socket) { link = Link.Online; retries = 0 } }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (ws === socket) handle(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { if (ws === socket) dropped() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (ws === socket) dropped() }
            }
        })
        ws = socket
    }

    private fun handle(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "sessions" -> {
                val arr = msg.getJSONArray("sessions")
                onSessions((0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    SessionInfo(s.getString("id"), s.getString("name"), s.getString("shell"),
                        s.optJSONObject("viewers")?.optInt("pc") ?: 0)
                })
            }
            "created" -> onCreated(msg.getString("id"))
            "projects" -> {
                val arr = msg.getJSONArray("projects")
                projects = (0 until arr.length()).map { i ->
                    val p = arr.getJSONObject(i)
                    Project(p.getString("path"), p.getString("name"))
                }
            }
            "alert" -> onAlert(msg.getString("id"), msg.optString("name"), msg.optString("message"))
            "error" -> error = msg.optString("message")
        }
    }

    private fun dropped() {
        ws = null
        link = Link.Offline
        if (!running) return
        val delay = (1000L shl retries.coerceAtMost(3)).coerceAtMost(8000L)
        retries++
        main.postDelayed(reconnect, delay)
    }
}
