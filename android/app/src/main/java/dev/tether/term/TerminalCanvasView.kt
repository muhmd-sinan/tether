package dev.tether.term

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import dev.tether.net.SessionConnection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a session at phone size without resizing the PTY.
 *
 * Normal screen: output is reflowed to the view width (see [WrapLayout]) and
 * scrolls vertically, pinned to the bottom unless the user scrolls back.
 * Alternate screen (vim, less, htop): the true PTY grid is drawn at readable
 * size and panned in 2D, following the cursor.
 */
class TerminalCanvasView(context: Context) : View(context) {

    var sticky: StickyKeys? = null

    var connection: SessionConnection? = null
        set(value) {
            if (field === value) return
            field?.onUpdate = null
            field = value
            value?.onUpdate = ::onTerminalUpdate
            resetScroll()
            invalidate()
        }

    var fontSizeSp = 13f
        set(value) {
            val v = value.coerceIn(MIN_SP, MAX_SP)
            if (v == field) return
            field = v
            measureCells()
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val fillPaint = Paint()
    private var cellW = 1f
    private var cellH = 1f
    private var ascent = 0f
    private val asciiW = FloatArray(128)

    // Normal-screen scroll: null anchor = pinned to the newest line.
    private var anchor: Pos? = null
    private var anchorScrolled = 0L
    private var scrollAccum = 0f
    private var seenGeneration = -1
    private var lastAlt = false

    // Alternate-screen pan, in pixels.
    private var panX = 0f
    private var panY = 0f
    private var lastUserPan = 0L
    private var lastCursor = -1L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCells()
    }

    private fun measureCells() {
        textPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        cellW = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        ascent = -fm.ascent
        cellH = ceil(fm.descent - fm.ascent)
        val one = CharArray(1)
        for (i in asciiW.indices) {
            one[0] = i.toChar()
            asciiW[i] = textPaint.measureText(one, 0, 1)
        }
    }

    private fun onTerminalUpdate() = invalidate()

    private fun resetScroll() {
        anchor = null
        scrollAccum = 0f
        panX = 0f
        panY = 0f
        lastUserPan = 0L
        lastCursor = -1L
        seenGeneration = connection?.generation ?: -1
    }

    // ---- drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Palette.BG)
        val conn = connection ?: return
        val e = conn.emulator ?: return
        if (conn.generation != seenGeneration) resetScroll()
        val alt = e.isAlternateBufferActive
        if (alt != lastAlt) {
            lastAlt = alt
            anchor = null
            panX = 0f; panY = 0f; lastCursor = -1L
        }
        if (alt) drawGrid(canvas, e) else drawWrapped(canvas, e, conn)
    }

    private fun visibleRows() = max(1, (height / cellH).toInt())

    private fun layoutFor(e: TerminalEmulator) =
        WrapLayout(e.screen, e.mColumns, e.mRows, e.cursorRow, e.cursorCol, max(1, (width / cellW).toInt()))

    /** Current anchor in this frame's coordinates, accounting for lines that scrolled away. */
    private fun resolveAnchor(l: WrapLayout, conn: SessionConnection): Pos? {
        val a = anchor ?: return null
        val shift = (conn.scrolled - anchorScrolled).toInt()
        anchorScrolled = conn.scrolled
        val p = l.clamp(Pos(a.row - shift, a.sub))
        anchor = if (p >= l.end()) null else p
        return anchor
    }

    private fun drawWrapped(c: Canvas, e: TerminalEmulator, conn: SessionConnection) {
        val l = layoutFor(e)
        val rows = visibleRows()
        val a = resolveAnchor(l, conn)
        val lines = l.window(a ?: l.end(), rows)
        // Pinned with a full screen: hug the bottom so the prompt sits above the keys.
        val y0 = if (a == null && lines.size == rows) height - rows * cellH else 0f
        val cursorOn = e.shouldCursorBeVisible()
        lines.forEachIndexed { i, p ->
            var x = 0f
            val y = y0 + i * cellH
            for (s in l.line(p).segments) {
                val cursor = if (cursorOn && s.row == e.cursorRow) e.cursorCol else -1
                drawRow(c, e, l.rowObj(s.row), s.startCol, s.endCol, x, y, cursor)
                x += s.width * cellW
            }
        }
    }

    private fun drawGrid(c: Canvas, e: TerminalEmulator) {
        followCursor(e)
        clampPan(e)
        c.save()
        c.translate(-panX, -panY)
        val firstRow = (panY / cellH).toInt().coerceAtLeast(0)
        val lastRow = min(e.mRows - 1, ((panY + height) / cellH).toInt())
        val firstCol = ((panX / cellW).toInt() - 1).coerceAtLeast(0)
        val endCol = min(e.mColumns, ((panX + width) / cellW).toInt() + 2)
        val cursorOn = e.shouldCursorBeVisible()
        for (r in firstRow..lastRow) {
            val row = e.screen.allocateFullLineIfNecessary(e.screen.externalToInternalRow(r))
            drawRow(c, e, row, firstCol, endCol, firstCol * cellW, r * cellH, if (cursorOn && r == e.cursorRow) e.cursorCol else -1)
        }
        c.restore()
    }

    /** Draw PTY columns [startCol, endCol) of [row] with column startCol at x0. */
    private fun drawRow(c: Canvas, e: TerminalEmulator, row: TerminalRow, startCol: Int, endCol: Int,
                        x0: Float, y: Float, cursorCol: Int) {
        val text = row.mText
        val used = row.spaceUsed
        var col = 0
        var i = 0
        while (col < endCol && i < used) {
            val ch = text[i]
            val n = if (Character.isHighSurrogate(ch) && i + 1 < used) 2 else 1
            val cp = if (n == 2) Character.toCodePoint(ch, text[i + 1]) else ch.code
            val w = WcWidth.width(cp).coerceIn(1, 2)
            var j = i + n
            while (j < used && WcWidth.width(text, j) <= 0) j += if (Character.isHighSurrogate(text[j])) 2 else 1
            if (col >= startCol) {
                drawCell(c, e, text, i, j - i, cp, w, row.getStyle(col), x0 + (col - startCol) * cellW, y, col == cursorCol)
            }
            col += w
            i = j
        }
    }

    private fun color(index: Int, palette: IntArray) = if (index ushr 24 == 0xff) index else palette[index]

    private fun drawCell(c: Canvas, e: TerminalEmulator, text: CharArray, idx: Int, len: Int, cp: Int, w: Int,
                         style: Long, x: Float, y: Float, cursor: Boolean) {
        val palette = e.mColors.mCurrentColors
        val effect = TextStyle.decodeEffect(style)
        var fgIndex = TextStyle.decodeForeColor(style)
        val bold = effect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_BLINK) != 0
        if (bold && fgIndex in 0..7) fgIndex += 8
        var fg = color(fgIndex, palette)
        var bg = color(TextStyle.decodeBackColor(style), palette)
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) { val t = fg; fg = bg; bg = t }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_DIM != 0) {
            fg = (fg and 0xff000000.toInt()) or
                ((fg shr 16 and 0xff) * 2 / 3 shl 16) or ((fg shr 8 and 0xff) * 2 / 3 shl 8) or ((fg and 0xff) * 2 / 3)
        }
        if (effect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE != 0) fg = bg
        if (cursor) { bg = palette[TextStyle.COLOR_INDEX_CURSOR]; fg = palette[TextStyle.COLOR_INDEX_BACKGROUND] }

        val cw = cellW * w
        if (bg != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            fillPaint.color = bg
            c.drawRect(x, y, x + cw, y + cellH, fillPaint)
        }
        val underline = effect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE != 0
        if (cp == ' '.code && !underline) return
        textPaint.color = fg
        textPaint.isFakeBoldText = bold
        textPaint.textSkewX = if (effect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC != 0) -0.25f else 0f
        textPaint.isUnderlineText = underline
        textPaint.isStrikeThruText = effect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH != 0
        val measured = if (cp < 128) asciiW[cp] else textPaint.measureText(text, idx, len)
        if (measured > 0f && abs(measured - cw) > 0.5f) {
            // Glyph wider/narrower than its cells (emoji, CJK fallback): squeeze to fit.
            c.save()
            c.scale(cw / measured, 1f, x, y)
            c.drawText(text, idx, len, x, y + ascent, textPaint)
            c.restore()
        } else {
            c.drawText(text, idx, len, x, y + ascent, textPaint)
        }
    }

    // ---- alternate screen pan --------------------------------------------

    private fun followCursor(e: TerminalEmulator) {
        val key = e.cursorRow.toLong() shl 32 or e.cursorCol.toLong()
        if (key == lastCursor) return
        lastCursor = key
        if (SystemClock.uptimeMillis() - lastUserPan < 1500) return
        val cx = e.cursorCol * cellW
        val cy = e.cursorRow * cellH
        val mx = cellW * 4
        val my = cellH * 2
        if (cx < panX + mx) panX = cx - mx else if (cx + cellW > panX + width - mx) panX = cx + cellW - width + mx
        if (cy < panY + my) panY = cy - my else if (cy + cellH > panY + height - my) panY = cy + cellH - height + my
    }

    private fun clampPan(e: TerminalEmulator) {
        panX = panX.coerceIn(0f, max(0f, e.mColumns * cellW - width))
        panY = panY.coerceIn(0f, max(0f, e.mRows * cellH - height))
    }

    // ---- gestures (driven from Compose) -----------------------------------

    /** Vertical drag on the normal screen; positive = finger down = older output. Returns consumed px. */
    fun scrollByPixels(dy: Float): Float {
        val conn = connection ?: return 0f
        val e = conn.emulator ?: return 0f
        if (e.isAlternateBufferActive) return 0f
        scrollAccum += dy
        val lines = (scrollAccum / cellH).toInt()
        if (lines == 0) return dy
        scrollAccum -= lines * cellH
        val moved = moveAnchor(e, conn, -lines)
        invalidate()
        if (moved == 0) {
            scrollAccum = 0f
            return 0f // at an edge: stop the fling
        }
        return dy
    }

    private fun moveAnchor(e: TerminalEmulator, conn: SessionConnection, delta: Int): Int {
        val l = layoutFor(e)
        val end = l.end()
        var p = resolveAnchor(l, conn) ?: end
        var moved = 0
        if (delta < 0) {
            val min = l.minAnchor(visibleRows())
            while (moved > delta && p > min) { p = l.prev(p) ?: break; moved-- }
        } else {
            while (moved < delta) { p = l.next(p, end) ?: break; moved++ }
        }
        anchor = if (p >= end) null else p
        anchorScrolled = conn.scrolled
        return moved
    }

    /** Drag on the alternate screen. */
    fun panBy(dx: Float, dy: Float) {
        panX -= dx
        panY -= dy
        lastUserPan = SystemClock.uptimeMillis()
        invalidate()
    }

    fun transcript(): String = connection?.emulator?.screen?.transcriptText ?: ""

    // ---- keyboard ---------------------------------------------------------

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    private fun type(text: String) {
        val conn = connection ?: return
        sticky?.sendText(conn, text) ?: conn.send(text.replace('\n', '\r'))
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password: no autocorrect or suggestions, keys arrive one by one.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                super.commitText(text, newCursorPosition)
                flush()
                return true
            }

            override fun finishComposingText(): Boolean {
                super.finishComposingText()
                flush()
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(max(1, beforeLength)) { connection?.send("\u007f") }
                return true
            }

            private fun flush() {
                val ed = editable ?: return
                val s = ed.toString()
                ed.clear()
                if (s.isNotEmpty()) type(s)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val conn = connection
        if (conn == null || event.isSystem) return super.onKeyDown(keyCode, event)
        var mods = 0
        if (event.isCtrlPressed) mods = mods or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed) mods = mods or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) mods = mods or KeyHandler.KEYMOD_SHIFT
        val keys = sticky ?: StickyKeys()
        keys.code(keyCode, conn.emulator, mods)?.let { conn.send(it); return true }

        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val uc = event.getUnicodeChar(meta) and KeyCharacterMap.COMBINING_ACCENT_MASK.inv()
        if (uc == 0) return super.onKeyDown(keyCode, event)
        var s = String(Character.toChars(uc))
        if (event.isCtrlPressed) StickyKeys.controlCode(uc)?.let { s = it.toString() }
        if (event.isAltPressed) s = "\u001b$s"
        type(s)
        return true
    }

    companion object {
        const val MIN_SP = 8f
        const val MAX_SP = 24f
    }
}
