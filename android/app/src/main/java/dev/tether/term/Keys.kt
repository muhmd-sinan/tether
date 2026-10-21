package dev.tether.term

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.KeyHandler
import dev.tether.net.SessionConnection

/** Ctrl/Alt from the extra-keys row: armed by a tap, consumed by the next key. */
class StickyKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    private fun clear() { ctrl = false; alt = false }

    /** Typed text with Ctrl/Alt applied to its first character. */
    fun applyTo(text: String): String {
        if ((!ctrl && !alt) || text.isEmpty()) return text
        val first = text.codePointAt(0)
        val rest = text.substring(Character.charCount(first))
        var head = String(Character.toChars(first))
        if (ctrl) controlCode(first)?.let { head = it.toString() }
        if (alt) head = "\u001b$head"
        clear()
        return head + rest
    }

    /** Escape sequence for a special key (arrows, Tab, Esc...), honouring sticky + physical modifiers. */
    fun code(keyCode: Int, emulator: com.termux.terminal.TerminalEmulator?, metaMods: Int = 0): String? {
        var mods = metaMods
        if (ctrl) mods = mods or KeyHandler.KEYMOD_CTRL
        if (alt) mods = mods or KeyHandler.KEYMOD_ALT
        val code = KeyHandler.getCode(keyCode, mods,
            emulator?.isCursorKeysApplicationMode ?: false, emulator?.isKeypadApplicationMode ?: false)
        if (code != null) clear()
        return code
    }

    fun sendKey(conn: SessionConnection?, keyCode: Int) {
        conn ?: return
        code(keyCode, conn.emulator)?.let(conn::send)
    }

    fun sendText(conn: SessionConnection?, text: String) {
        conn?.send(applyTo(text.replace("\r\n", "\r").replace('\n', '\r')))
    }

    companion object {
        fun controlCode(cp: Int): Char? = when (cp) {
            in 'a'.code..'z'.code -> (cp - 'a'.code + 1).toChar()
            in 'A'.code..'Z'.code -> (cp - 'A'.code + 1).toChar()
            ' '.code, '2'.code, '@'.code -> 0.toChar()
            '['.code, '3'.code -> 27.toChar()
            '\\'.code, '4'.code -> 28.toChar()
            ']'.code, '5'.code -> 29.toChar()
            '^'.code, '6'.code -> 30.toChar()
            '_'.code, '7'.code, '/'.code -> 31.toChar()
            '8'.code, '?'.code -> 127.toChar()
            else -> null
        }
    }
}
