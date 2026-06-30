package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StrokeSimplifierTest {

    @Test
    fun `zero spacing keeps every point`() {
        val pts = listOf(0f to 0f, 1f to 0f, 2f to 0f, 3f to 0f)
        assertEquals(listOf(0, 1, 2, 3), StrokeSimplifier.keptIndices(pts, 0f))
    }

    @Test
    fun `two-point stroke is never thinned`() {
        val pts = listOf(0f to 0f, 1f to 0f)
        assertEquals(listOf(0, 1), StrokeSimplifier.keptIndices(pts, 100f))
    }

    @Test
    fun `points closer than the spacing are dropped, endpoints always kept`() {
        // Points 1 unit apart; min spacing 2 keeps every other interior point, plus first + last.
        val pts = (0..6).map { it.toFloat() to 0f } // x = 0..6
        // From 0: next kept is x=2 (>=2 away), then x=4, then x=6 forced as the last point.
        assertEquals(listOf(0, 2, 4, 6), StrokeSimplifier.keptIndices(pts, 2f))
    }

    @Test
    fun `the final point is forced even when close to the last kept point`() {
        // 0, then 5 (kept), then 6 is within spacing of 5 but is the last point → forced.
        val pts = listOf(0f to 0f, 5f to 0f, 6f to 0f)
        assertEquals(listOf(0, 1, 2), StrokeSimplifier.keptIndices(pts, 3f))
    }

    @Test
    fun `spacing larger than the stroke collapses to first and last`() {
        val pts = (0..5).map { it.toFloat() to 0f }
        assertEquals(listOf(0, 5), StrokeSimplifier.keptIndices(pts, 100f))
    }
}
