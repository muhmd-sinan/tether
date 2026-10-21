package dev.tether.term

/**
 * Stand-ins for symbols that older phone fonts lack, such as the ⏵⏵ Claude
 * Code puts before its permission mode. Each list ends in ASCII so there is
 * always something to draw.
 */
object Glyphs {
    private val lookalikes = mapOf(
        0x23F4 to listOf("◄", "<"),
        0x23F5 to listOf("►", ">"),
        0x23F6 to listOf("▲", "^"),
        0x23F7 to listOf("▼", "v"),
        0x23F8 to listOf("‖", "|"),
        0x23F9 to listOf("■", "#"),
        0x23FA to listOf("●", "*"),
    )

    fun lookalikes(cp: Int): List<String> = lookalikes[cp] ?: emptyList()
}
