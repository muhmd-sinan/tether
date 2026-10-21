package dev.tether.term

import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalRow
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth

/** A run of PTY columns [startCol, endCol) on one buffer row. */
data class Segment(val row: Int, val startCol: Int, val endCol: Int) {
    val width get() = endCol - startCol
}

/** One line on the phone screen; may stitch together several PTY rows. */
class DisplayLine(val segments: List<Segment>) {
    val width get() = segments.sumOf { it.width }
}

/** Display position: [row] is the first PTY row of a logical line, [sub] the display line inside it. */
data class Pos(val row: Int, val sub: Int) : Comparable<Pos> {
    override fun compareTo(other: Pos) = compareValuesBy(this, other, { it.row }, { it.sub })
}

/**
 * Reflows the normal-screen buffer to a phone width without touching the PTY.
 *
 * PTY rows chained by the emulator's line-wrap flag form one logical line;
 * that line is cut into display lines of at most [width] cells, never through
 * a wide character. Rows use the emulator's external coordinates
 * (negative = scrollback). Build one per frame; results are cached inside.
 */
class WrapLayout(
    private val screen: TerminalBuffer,
    private val ptyCols: Int,
    private val ptyRows: Int,
    private val cursorRow: Int,
    private val cursorCol: Int,
    val width: Int,
) {
    val top: Int = -screen.activeTranscriptRows
    private val split = HashMap<Int, List<DisplayLine>>()

    fun rowObj(row: Int): TerminalRow = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row))

    private fun wraps(row: Int) = row < ptyRows - 1 && screen.getLineWrap(row)

    fun lineStart(row: Int): Int {
        var r = row
        while (r > top && screen.getLineWrap(r - 1)) r--
        return r
    }

    /** Cell widths per column: 1 or 2 for a glyph start, 0 for the second half of a wide glyph. */
    fun columnWidths(row: Int): IntArray {
        val line = rowObj(row)
        val text = line.mText
        val used = line.spaceUsed
        val widths = IntArray(ptyCols)
        var col = 0
        var i = 0
        while (col < ptyCols && i < used) {
            val c = text[i]
            val n = if (Character.isHighSurrogate(c) && i + 1 < used) 2 else 1
            val cp = if (n == 2) Character.toCodePoint(c, text[i + 1]) else c.code
            val w = WcWidth.width(cp).coerceAtLeast(1).coerceAtMost(2)
            widths[col] = w
            if (w == 2 && col + 1 < ptyCols) widths[col + 1] = 0
            col += w
            i += n
            while (i < used && WcWidth.width(text, i) <= 0) i += if (Character.isHighSurrogate(text[i])) 2 else 1
        }
        while (col < ptyCols) widths[col++] = 1
        return widths
    }

    /** Columns that carry content: last non-space glyph, coloured cell, or the cursor. */
    fun usedCols(row: Int): Int {
        val line = rowObj(row)
        val widths = columnWidths(row)
        var last = 0
        var col = 0
        var i = 0
        val text = line.mText
        val used = line.spaceUsed
        while (col < ptyCols && i < used) {
            val c = text[i]
            val n = if (Character.isHighSurrogate(c) && i + 1 < used) 2 else 1
            val w = widths[col].coerceAtLeast(1)
            val style = line.getStyle(col)
            val bg = TextStyle.decodeBackColor(style)
            val inverse = TextStyle.decodeEffect(style) and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0
            if (c != ' ' || bg != TextStyle.COLOR_INDEX_BACKGROUND || inverse) last = col + w
            col += w
            i += n
            while (i < used && WcWidth.width(text, i) <= 0) i += if (Character.isHighSurrogate(text[i])) 2 else 1
        }
        if (row == cursorRow) last = maxOf(last, cursorCol + 1)
        return last.coerceAtMost(ptyCols)
    }

    /** Display lines of the logical line starting at [start]. */
    fun lines(start: Int): List<DisplayLine> = split.getOrPut(start) { splitLine(start) }

    private fun splitLine(start: Int): List<DisplayLine> {
        val out = ArrayList<DisplayLine>()
        var current = ArrayList<Segment>()
        var room = width
        var row = start
        while (true) {
            val wrapped = wraps(row)
            val rowWidth = if (wrapped) ptyCols else usedCols(row)
            val widths = columnWidths(row)
            var col = 0
            while (col < rowWidth) {
                var end = minOf(rowWidth, col + room)
                // Don't cut a wide glyph in half; push it to the next line.
                if (end < rowWidth && end > col && widths[end] == 0) end--
                if (end == col) {
                    if (current.isEmpty()) end = minOf(rowWidth, col + 2) // width < 2: overflow
                    else {
                        out.add(DisplayLine(current)); current = ArrayList(); room = width
                        continue
                    }
                }
                current.add(Segment(row, col, end))
                room -= end - col
                col = end
                if (room <= 0) {
                    out.add(DisplayLine(current)); current = ArrayList(); room = width
                }
            }
            if (!wrapped) break
            row++
        }
        if (current.isNotEmpty() || out.isEmpty()) out.add(DisplayLine(current))
        return out
    }

    private fun nextLineStart(start: Int): Int {
        var r = start
        while (wraps(r)) r++
        return r + 1
    }

    /** Last row worth showing: skip blank rows below the cursor (e.g. after cls). */
    fun lastContentRow(): Int {
        var r = ptyRows - 1
        while (r > cursorRow && usedCols(r) == 0) r--
        return r
    }

    fun end(): Pos {
        val start = lineStart(lastContentRow())
        return Pos(start, lines(start).size - 1)
    }

    fun prev(p: Pos): Pos? {
        if (p.sub > 0) return Pos(p.row, p.sub - 1)
        if (p.row <= top) return null
        val s = lineStart(p.row - 1)
        return Pos(s, lines(s).size - 1)
    }

    fun next(p: Pos, limit: Pos = end()): Pos? {
        if (p >= limit) return null
        if (p.sub < lines(p.row).size - 1) return Pos(p.row, p.sub + 1)
        return Pos(nextLineStart(p.row), 0)
    }

    fun line(p: Pos): DisplayLine = lines(p.row)[p.sub]

    /** Up to [count] display positions ending at [last], oldest first. */
    fun window(last: Pos, count: Int): List<Pos> {
        val out = ArrayDeque<Pos>()
        var p: Pos? = last
        while (p != null && out.size < count) {
            out.addFirst(p)
            p = prev(p)
        }
        return out
    }

    /** Lowest position that still leaves a full screen above it: the scroll-back limit. */
    fun minAnchor(visibleRows: Int): Pos {
        var p = Pos(lineStart(top), 0)
        val limit = end()
        repeat(visibleRows - 1) { p = next(p, limit) ?: return limit }
        return p
    }

    /** Clamp a stored position into this frame's buffer (rows may have scrolled away). */
    fun clamp(p: Pos): Pos {
        if (p.row < top) return Pos(lineStart(top), 0)
        val s = lineStart(p.row)
        val limit = end()
        val q = Pos(s, if (s == p.row) p.sub.coerceIn(0, lines(s).size - 1) else 0)
        return if (q > limit) limit else q
    }

    /** Cell offset of the cursor within display line [p], or -1 if not on it. */
    fun cursorOffset(p: Pos): Int {
        var off = 0
        val segs = line(p).segments
        for ((i, s) in segs.withIndex()) {
            if (s.row == cursorRow) {
                val lastSeg = i == segs.size - 1
                if (cursorCol >= s.startCol && (cursorCol < s.endCol || (lastSeg && cursorCol == s.endCol && !wraps(s.row))))
                    return off + cursorCol - s.startCol
            }
            off += s.width
        }
        return -1
    }
}
