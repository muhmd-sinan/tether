package dev.tether.term

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WrapLayoutTest {
    private class Sink : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }

    private fun emulator(cols: Int, rows: Int, text: String): TerminalEmulator {
        val e = TerminalEmulator(Sink(), cols, rows, 500, NoopClient)
        val bytes = text.toByteArray()
        e.append(bytes, bytes.size)
        return e
    }

    private fun layout(e: TerminalEmulator, width: Int) =
        WrapLayout(e.screen, e.mColumns, e.mRows, e.cursorRow, e.cursorCol, width)

    private fun WrapLayout.text(p: Pos): String = buildString {
        for (s in line(p).segments) {
            val widths = columnWidths(s.row)
            val row = rowObj(s.row)
            for (c in s.startCol until s.endCol) {
                if (widths[c] == 0) continue
                val i = row.findStartOfColumn(c)
                append(Character.toChars(Character.codePointAt(row.mText, i)))
            }
        }
    }

    private fun WrapLayout.all(): List<String> = window(end(), 10_000).map { text(it) }

    @Test
    fun shortLinesPassThrough() {
        val l = layout(emulator(120, 10, "hello\r\nworld\r\n> "), 40)
        assertEquals(listOf("hello", "world", ">  "), l.all())
    }

    @Test
    fun longLineWrapsAtPhoneWidth() {
        val line = "0123456789".repeat(10) // 100 cells, fits one 120-col PTY row
        val l = layout(emulator(120, 10, "$line\r\n"), 40)
        val lines = l.all()
        assertEquals(listOf(line.substring(0, 40), line.substring(40, 80), line.substring(80), " "), lines)
    }

    @Test
    fun ptyWrappedLineIsJoinedOnWideScreen() {
        val line = "abcdefghij".repeat(3) // 30 cells on a 20-col PTY: autowraps once
        val l = layout(emulator(20, 10, "$line\r\n"), 50)
        assertEquals(listOf(line, " "), l.all())
    }

    @Test
    fun wideGlyphIsNeverSplit() {
        val l = layout(emulator(120, 5, "ab中文\r\n"), 3)
        val lines = l.all()
        assertEquals(listOf("ab", "中", "文", " "), lines)
    }

    @Test
    fun cursorMapsIntoWrappedLine() {
        val prompt = "x".repeat(50)
        val e = emulator(120, 10, prompt) // cursor sits at column 50 of row 0
        val l = layout(e, 40)
        val end = l.end()
        assertEquals(Pos(0, 1), end)
        assertEquals(10, l.cursorOffset(end))
    }

    @Test
    fun blankRowsBelowCursorAreHidden() {
        val e = emulator(120, 10, "\u001b[2J\u001b[Hprompt> ")
        val l = layout(e, 40)
        assertEquals(Pos(0, 0), l.end())
        assertEquals(listOf("prompt>  "), l.all())
    }

    /** Real ConPTY + PowerShell 5.1 bytes captured from the agent (a 150-char line on a 120-col PTY). */
    @Test
    fun realConptyLongLineReflowsAsOneLine() {
        val bytes = javaClass.getResourceAsStream("/conpty_powershell.bin")!!.readBytes()
        val e = TerminalEmulator(Sink(), 120, 30, 500, NoopClient)
        e.append(bytes, bytes.size)
        val l = layout(e, 40)
        val positions = l.window(l.end(), 10_000)
        val logical = positions.groupBy { it.row }.values.map { ps -> ps.joinToString("") { l.text(it) }.trimEnd() }
        val long = "W".repeat(150)
        assertTrue("output line should be one logical line: $logical", logical.contains(long))
        // Display lines of just the output line (the echoed command also contains W runs).
        val outputRow = positions.groupBy { it.row }.entries
            .first { (_, ps) -> ps.joinToString("") { l.text(it) }.trimEnd() == long }.value
        assertEquals(listOf(40, 40, 40, 30), outputRow.map { l.text(it).trimEnd().length })
        assertTrue(logical.any { it.contains("Write-Output '$long'") })
        assertTrue(logical.contains("after-long"))
    }

    @Test
    fun scrollbackIsReachableAndClamped() {
        val sb = StringBuilder()
        for (i in 1..20) sb.append("line $i\r\n")
        val l = layout(emulator(120, 5, sb.toString()), 40)
        assertTrue(l.top < 0)
        val first = l.window(l.end(), 10_000).first()
        assertEquals("line 1", l.text(first))
        // A position that scrolled out of the transcript clamps to the top.
        assertEquals(Pos(l.top, 0), l.clamp(Pos(l.top - 50, 3)))
        // minAnchor leaves exactly one screen of lines above and including it.
        assertEquals(5, l.window(l.minAnchor(5), 10_000).size)
    }
}
