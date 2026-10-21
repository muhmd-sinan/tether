package dev.tether.term

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.util.Properties

/** The emulator wants a session client for logging and cursor style; we need neither. */
object NoopClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {}
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) {}
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int = 0
    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
    override fun logStackTrace(tag: String?, e: Exception?) {}
}

/** Warm palette matching the app. Install before creating any emulator. */
object Palette {
    const val BG = 0xFF151311.toInt()
    const val FG = 0xFFECE6DC.toInt()
    const val CURSOR = 0xFFF0743E.toInt()

    private val ansi = listOf(
        "#2A2622", "#E06C5F", "#8FBF8A", "#E2B95A", "#7AA2D6", "#C68BC2", "#6FBFB5", "#D9D1C6",
        "#5E5750", "#F28B7D", "#A9D6A3", "#F0CC73", "#98BDEB", "#DBA6D7", "#8ED6CC", "#F5F0E8",
    )

    fun install() {
        val p = Properties()
        p["foreground"] = "#ECE6DC"
        p["background"] = "#151311"
        p["cursor"] = "#F0743E"
        ansi.forEachIndexed { i, c -> p["color$i"] = c }
        TerminalColors.COLOR_SCHEME.updateWith(p)
    }
}
