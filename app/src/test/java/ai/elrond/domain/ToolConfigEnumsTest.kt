package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FA-23 tool-config enums: defaults, name round-trips, and the shared pattern specs. */
class ToolConfigEnumsTest {

    @Test
    fun `defaults match the spec`() {
        assertEquals(InkLineType.SOLID, InkLineType.DEFAULT)
        assertEquals(PenColor.BLUE, PenColor.DEFAULT)
        assertEquals(HighlighterColor.YELLOW, HighlighterColor.DEFAULT)
        assertEquals(HighlighterWidth.STANDARD, HighlighterWidth.DEFAULT)
        assertEquals(PencilLead.HB, PencilLead.DEFAULT)
    }

    @Test
    fun `pencil leads run light to dark with HB matching the pre-selector colour`() {
        assertEquals(listOf("2H", "H", "HB", "B", "2B"), PencilLead.entries.map { it.label })
        assertEquals(0xE043484E.toInt(), PencilLead.HB.argb) // pre-lead pencil colour, unchanged
        // Alpha strictly increases light → dark, so softer leads read denser.
        val alphas = PencilLead.entries.map { (it.argb ushr 24) and 0xFF }
        assertTrue(alphas.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `default pen colour is the pre-FA-23 user ink navy`() {
        assertEquals(0xFF1A237E.toInt(), PenColor.DEFAULT.argb)
    }

    @Test
    fun `fromName round-trips and falls back to default on bad names`() {
        assertEquals(InkLineType.DASH_DOT, InkLineType.fromName("DASH_DOT"))
        assertEquals(InkLineType.DEFAULT, InkLineType.fromName("nonsense"))
        assertEquals(InkLineType.DEFAULT, InkLineType.fromName(null))
        assertEquals(PenColor.RED, PenColor.fromName("RED"))
        assertEquals(PenColor.DEFAULT, PenColor.fromName("MAGENTA"))
        assertEquals(HighlighterColor.PINK, HighlighterColor.fromName("PINK"))
        assertEquals(HighlighterColor.DEFAULT, HighlighterColor.fromName(""))
        assertEquals(HighlighterWidth.THICK, HighlighterWidth.fromName("THICK"))
        assertEquals(HighlighterWidth.DEFAULT, HighlighterWidth.fromName(null))
        assertEquals(PencilLead.TWO_B, PencilLead.fromName("TWO_B"))
        assertEquals(PencilLead.DEFAULT, PencilLead.fromName("9H"))
        assertEquals(PencilLead.DEFAULT, PencilLead.fromName(null))
    }

    @Test
    fun `patterned types expose runs and solid exposes none`() {
        assertTrue(InkLineType.SOLID.patternRuns.isEmpty())
        assertEquals(listOf(PatternRun(6f, 3f)), InkLineType.DASHED.patternRuns)
        // Centreline alternates long-short; dotted and dash-dot use zero-length "dot" runs.
        assertEquals(2, InkLineType.CENTRELINE.patternRuns.size)
        assertTrue(InkLineType.DOTTED.patternRuns.single().drawLen == 0f)
        assertTrue(InkLineType.DASH_DOT.patternRuns.last().drawLen == 0f)
    }

    @Test
    fun `highlighter colours carry a translucent alpha`() {
        HighlighterColor.entries.forEach { c ->
            val alpha = (c.argb ushr 24) and 0xFF
            assertTrue("${c.name} should be translucent", alpha in 1..0x80)
        }
    }
}
