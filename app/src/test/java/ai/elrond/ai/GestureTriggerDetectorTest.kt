package ai.elrond.ai

import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.domain.GestureTriggerDetector.Point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureTriggerDetectorTest {

    /** A ~100×100 square loop (9 points, returns to start) — a clean lasso. */
    private val squareLoop = listOf(
        Point(0f, 0f), Point(50f, 0f), Point(100f, 0f),
        Point(100f, 50f), Point(100f, 100f),
        Point(50f, 100f), Point(0f, 100f),
        Point(0f, 50f), Point(0f, 0f),
    )

    @Test
    fun `a closed square loop is a lasso`() {
        assertTrue(GestureTriggerDetector.isLasso(squareLoop))
    }

    @Test
    fun `a straight back-and-forth scribble is not a lasso`() {
        // Closed (returns to start) but encloses no area.
        val line = listOf(
            Point(0f, 0f), Point(20f, 0f), Point(40f, 0f), Point(60f, 0f),
            Point(80f, 0f), Point(100f, 0f), Point(50f, 0f), Point(0f, 0f),
        )
        assertFalse(GestureTriggerDetector.isLasso(line))
    }

    @Test
    fun `an open arc is not a lasso`() {
        // Has area but the ends are far apart — not closed.
        val arc = listOf(
            Point(0f, 0f), Point(50f, 0f), Point(100f, 0f),
            Point(100f, 50f), Point(100f, 100f),
            Point(50f, 100f), Point(0f, 100f), Point(0f, 60f),
        )
        assertFalse(GestureTriggerDetector.isLasso(arc))
    }

    @Test
    fun `too few points is not a lasso`() {
        assertFalse(GestureTriggerDetector.isLasso(listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f))))
    }

    @Test
    fun `a tiny loop below the size floor is not a lasso`() {
        val tiny = listOf(
            Point(0f, 0f), Point(5f, 0f), Point(10f, 0f), Point(10f, 5f),
            Point(10f, 10f), Point(5f, 10f), Point(0f, 10f), Point(0f, 0f),
        )
        assertFalse(GestureTriggerDetector.isLasso(tiny))
    }

    private val square = listOf(Point(0f, 0f), Point(100f, 0f), Point(100f, 100f), Point(0f, 100f))

    @Test
    fun `contains is true inside and false outside the polygon`() {
        assertTrue(GestureTriggerDetector.contains(square, Point(50f, 50f)))
        assertFalse(GestureTriggerDetector.contains(square, Point(150f, 50f)))
        assertFalse(GestureTriggerDetector.contains(square, Point(50f, 150f)))
        assertFalse(GestureTriggerDetector.contains(square, Point(-10f, 50f)))
    }

    @Test
    fun `enclosedIndices keeps only centroids inside the lasso`() {
        val centroids = listOf(
            Point(50f, 50f),   // inside
            Point(200f, 200f), // outside
            Point(10f, 90f),   // inside
        )
        assertEquals(listOf(0, 2), GestureTriggerDetector.enclosedIndices(square, centroids))
    }
}
