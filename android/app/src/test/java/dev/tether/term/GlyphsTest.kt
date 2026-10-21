package dev.tether.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphsTest {
    @Test
    fun claudeModeMarkerHasStandIns() {
        assertEquals(listOf("►", ">"), Glyphs.lookalikes(0x23F5))
    }

    @Test
    fun everyListEndsInAscii() {
        for (cp in 0x23F4..0x23FA) {
            val last = Glyphs.lookalikes(cp).last()
            assertTrue("U+%04X".format(cp), last.all { it.code < 128 })
        }
    }

    @Test
    fun unknownSymbolsAreLeftAlone() {
        assertTrue(Glyphs.lookalikes('A'.code).isEmpty())
    }
}
