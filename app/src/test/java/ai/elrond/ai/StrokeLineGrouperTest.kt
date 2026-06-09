package ai.elrond.ai

import ai.elrond.ai.StrokeLineGrouper.Span
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrokeLineGrouperTest {

    @Test
    fun `empty input produces no lines`() {
        assertTrue(StrokeLineGrouper.groupIntoLines(emptyList()).isEmpty())
    }

    @Test
    fun `vertically separated strokes form separate lines`() {
        val lines = StrokeLineGrouper.groupIntoLines(
            listOf(
                Span(0f, 40f),    // line 1
                Span(5f, 45f),    // line 1
                Span(100f, 140f), // line 2
            ),
        )
        assertEquals(listOf(listOf(0, 1), listOf(2)), lines)
    }

    @Test
    fun `lines are ordered top to bottom regardless of draw order`() {
        val lines = StrokeLineGrouper.groupIntoLines(
            listOf(
                Span(200f, 240f), // drawn first, but lowest on the page
                Span(0f, 40f),    // drawn second, topmost
            ),
        )
        assertEquals(listOf(listOf(1), listOf(0)), lines)
    }

    @Test
    fun `overlapping spans merge into one line`() {
        val lines = StrokeLineGrouper.groupIntoLines(
            listOf(
                Span(0f, 50f),
                Span(30f, 80f),  // overlaps first
                Span(60f, 100f), // overlaps merged range
            ),
        )
        assertEquals(listOf(listOf(0, 1, 2)), lines)
    }

    @Test
    fun `same line with horizontal word gaps stays one line`() {
        // Words on the same baseline have near-identical vertical spans.
        val lines = StrokeLineGrouper.groupIntoLines(
            listOf(Span(10f, 50f), Span(12f, 48f), Span(8f, 52f)),
        )
        assertEquals(1, lines.size)
        assertEquals(listOf(0, 1, 2), lines.single())
    }

    // --- blockAbove (multi-line question segmentation) ---

    /** Three evenly-spaced lines of height ~20, small ~10px gaps. */
    private val evenlySpaced = listOf(
        Span(0f, 20f),    // line 0
        Span(30f, 50f),   // line 1 (gap 10 below line 0)
        Span(60f, 80f),   // line 2 (gap 10 below line 1)
        Span(90f, 110f),  // line 3 = trigger
    )

    @Test
    fun `blockAbove returns empty for the first line or out of range`() {
        assertTrue(StrokeLineGrouper.blockAbove(evenlySpaced, triggerIndex = 0).isEmpty())
        assertTrue(StrokeLineGrouper.blockAbove(evenlySpaced, triggerIndex = 99).isEmpty())
    }

    @Test
    fun `blockAbove gathers the contiguous block above the trigger`() {
        // Tight, even spacing → all three lines above form one multi-line question, top-to-bottom.
        assertEquals(listOf(0, 1, 2), StrokeLineGrouper.blockAbove(evenlySpaced, triggerIndex = 3))
    }

    @Test
    fun `blockAbove stops at a paragraph gap`() {
        val withGap = listOf(
            Span(0f, 20f),    // line 0 — separated by a big gap below
            Span(200f, 220f), // line 1 (gap 180 ≫ line height → paragraph break)
            Span(230f, 250f), // line 2
            Span(260f, 280f), // line 3 = trigger
        )
        // Only the tight block (lines 1,2) is the question; line 0 stays as context.
        assertEquals(listOf(1, 2), StrokeLineGrouper.blockAbove(withGap, triggerIndex = 3))
    }
}
