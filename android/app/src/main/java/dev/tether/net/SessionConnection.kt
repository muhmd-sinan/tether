package dev.tether.net

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import dev.tether.term.NoopClient
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * One phone tab attached to one PC session. Owns the emulator; all emulator
 * access happens on the main thread. On every (re)connect the agent replays
 * the scrollback, so a fresh emulator is built from the hello message.
 */
class SessionConnection(
    private val http: OkHttpClient,
    private val baseUrl: String,
    val id: String,
) {
    enum class State { Connecting, Live, Reconnecting, Ended }

    var state by mutableStateOf(State.Connecting)
        private set
    var altScreen by mutableStateOf(false)
        private set
    /** Bumps whenever the emulator is replaced, so views reset scroll state. */
    var generation by mutableIntStateOf(0)
        private set

    var emulator: TerminalEmulator? = null
        private set
    /** Lines pushed into scrollback since this emulator was created. */
    var scrolled = 0L
        private set
    var onUpdate: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var running = false
    private var live = false
    private var retries = 0
    private val reconnect = Runnable { if (running && ws == null) open() }

    /** Emulator replies (cursor reports etc.) go to the shell, but not during replay. */
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (live) ws?.send(data.toByteString(offset, count))
        }
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() { onUpdate?.invoke() }
    }

    fun start() {
        if (running || state == State.Ended) return
        running = true
        open()
    }

    fun stop() {
        running = false
        main.removeCallbacks(reconnect)
        ws?.close(1000, null)
        ws = null
        live = false
    }

    fun send(text: String) {
        if (text.isNotEmpty()) ws?.send(text.toByteArray().toByteString())
    }

    fun paste(text: String) {
        emulator?.paste(text)
    }

    private fun open() {
        state = if (emulator == null) State.Connecting else State.Reconnecting
        live = false
        val request = Request.Builder().url("$baseUrl/s/$id?role=phone").build()
        lateinit var socket: WebSocket
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                main.post { if (ws === socket) feed(data) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post { if (ws === socket) control(text) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                main.post { if (ws === socket) dropped(code) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { if (ws === socket) dropped(null) }
            }
        })
        ws = socket
    }

    private fun feed(data: ByteArray) {
        val e = emulator ?: return
        e.append(data, data.size)
        scrolled += e.scrollCounter
        e.clearScrollCounter()
        altScreen = e.isAlternateBufferActive
        onUpdate?.invoke()
    }

    private fun control(text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (msg.optString("type")) {
            "hello" -> {
                emulator = TerminalEmulator(output, msg.getInt("cols"), msg.getInt("rows"), TRANSCRIPT_ROWS, NoopClient)
                scrolled = 0
                altScreen = false
                generation++
                onUpdate?.invoke()
            }
            "live" -> {
                live = true
                retries = 0
                state = State.Live
                onUpdate?.invoke()
            }
            "size" -> {
                emulator?.resize(msg.getInt("cols"), msg.getInt("rows"))
                onUpdate?.invoke()
            }
            "exit" -> state = State.Ended
        }
    }

    private fun dropped(code: Int?) {
        ws = null
        live = false
        if (code == 4404 || state == State.Ended) {
            state = State.Ended
            running = false
            return
        }
        if (!running) return
        state = State.Reconnecting
        val delay = (1000L shl retries.coerceAtMost(3)).coerceAtMost(8000L)
        retries++
        main.postDelayed(reconnect, delay)
    }

    companion object {
        const val TRANSCRIPT_ROWS = 3000
    }
}
