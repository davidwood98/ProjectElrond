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
}
