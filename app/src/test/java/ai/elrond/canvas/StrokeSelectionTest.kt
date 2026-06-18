package ai.elrond.canvas

import ai.elrond.ai.GestureTriggerDetector
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure selection geometry — no ink natives (the [CanvasStroke.stroke] field is never read here). */
class StrokeSelectionTest {

    private fun cs(id: String, group: String? = null) = CanvasStroke(id, mockk(), group)
    private fun p(x: Float, y: Float) = GestureTriggerDetector.Point(x, y)
    private fun square(size: Float) = listOf(p(0f, 0f), p(size, 0f), p(size, size), p(0f, size))

    @Test
    fun `expandToGroups pulls in group-mates of any selected grouped stroke`() {
        val strokes = listOf(cs("a", "g1"), cs("b", "g1"), cs("c"), cs("d", "g2"))
        assertEquals(setOf("a", "b"), StrokeSelection.expandToGroups(setOf("a"), strokes))
    }

    @Test
    fun `expandToGroups leaves an ungrouped selection unchanged`() {
        val strokes = listOf(cs("a"), cs("b"), cs("c"))
        assertEquals(setOf("a", "b"), StrokeSelection.expandToGroups(setOf("a", "b"), strokes))
    }

    @Test
    fun `expandToGroups merges every touched group`() {
        val strokes = listOf(cs("a", "g1"), cs("b", "g1"), cs("c", "g2"), cs("d", "g2"), cs("e"))
        assertEquals(setOf("a", "b", "c", "d"), StrokeSelection.expandToGroups(setOf("a", "c"), strokes))
    }

    @Test
    fun `enclosedIds returns ids whose centroids fall inside the polygon`() {
        val ids = listOf("in", "out")
        val centroids = listOf(p(50f, 50f), p(500f, 500f))
        assertEquals(setOf("in"), StrokeSelection.enclosedIds(square(100f), ids, centroids))
    }

    @Test
    fun `union covers all the boxes`() {
        val union = StrokeSelection.union(
            listOf(SelectionBounds(0f, 0f, 10f, 10f), SelectionBounds(5f, -5f, 20f, 8f)),
        )
        assertEquals(SelectionBounds(0f, -5f, 20f, 10f), union)
    }

    @Test
    fun `union of nothing is null`() {
        assertNull(StrokeSelection.union(emptyList()))
    }

    @Test
    fun `bottom-right scale grows about the top-left pivot`() {
        val t = StrokeSelection.scaleTransform(
            Corner.BOTTOM_RIGHT, SelectionBounds(0f, 0f, 100f, 100f),
            dragX = 100f, dragY = 100f, lockRatio = false,
        )
        assertEquals(0f, t.pivotX)
        assertEquals(0f, t.pivotY)
        assertEquals(2f, t.scaleX, 1e-4f)
        assertEquals(2f, t.scaleY, 1e-4f)
        assertEquals(200f, t.applyX(100f), 1e-3f) // the dragged corner maps outward
    }

    @Test
    fun `top-left scale is about the bottom-right pivot`() {
        val t = StrokeSelection.scaleTransform(
            Corner.TOP_LEFT, SelectionBounds(0f, 0f, 100f, 100f),
            dragX = 50f, dragY = 50f, lockRatio = false,
        )
        assertEquals(100f, t.pivotX)
        assertEquals(100f, t.pivotY)
        assertEquals(0.5f, t.scaleX, 1e-4f) // dragging the TL corner inward halves the box
        assertEquals(0.5f, t.scaleY, 1e-4f)
    }

    @Test
    fun `lock ratio scales both axes uniformly`() {
        val t = StrokeSelection.scaleTransform(
            Corner.BOTTOM_RIGHT, SelectionBounds(0f, 0f, 100f, 100f),
            dragX = 100f, dragY = 0f, lockRatio = true,
        )
        assertEquals(t.scaleX, t.scaleY, 1e-4f)
    }

    @Test
    fun `scale is floored so the box cannot collapse or flip`() {
        val t = StrokeSelection.scaleTransform(
            Corner.BOTTOM_RIGHT, SelectionBounds(0f, 0f, 100f, 100f),
            dragX = -100000f, dragY = -100000f, lockRatio = false,
        )
        assertTrue(t.scaleX > 0f)
        assertTrue(t.scaleY > 0f)
    }

    @Test
    fun `identity transform maps points unchanged`() {
        assertTrue(LiveTransform.IDENTITY.isIdentity)
        assertEquals(7f, LiveTransform.IDENTITY.applyX(7f))
        assertEquals(3f, LiveTransform.IDENTITY.applyY(3f))
    }

    @Test
    fun `translate transform shifts points`() {
        val t = LiveTransform(dx = 5f, dy = -2f)
        assertFalse(t.isIdentity)
        assertEquals(15f, t.applyX(10f))
        assertEquals(8f, t.applyY(10f))
    }

    // --- FA-10 snap-back geometry ---

    @Test
    fun `snap-back triggers for a small move within the threshold`() {
        // 100x100 canvas, 10% threshold; a 9px move = 0.09 normalised < 0.1.
        assertTrue(StrokeSelection.shouldSnapBack(LiveTransform(dx = 9f), 100f, 100f, 0.1f))
    }

    @Test
    fun `snap-back does not trigger for a move beyond the threshold`() {
        assertFalse(StrokeSelection.shouldSnapBack(LiveTransform(dx = 11f), 100f, 100f, 0.1f))
    }

    @Test
    fun `snap-back is strict at exactly the threshold so the boundary commits`() {
        // 10px / 100px = 0.1, exactly == threshold → strict < is false → no snap.
        assertFalse(StrokeSelection.shouldSnapBack(LiveTransform(dx = 10f), 100f, 100f, 0.1f))
    }

    @Test
    fun `a zero threshold disables snap-back`() {
        assertFalse(StrokeSelection.shouldSnapBack(LiveTransform(dx = 1f, dy = 1f), 100f, 100f, 0f))
    }

    @Test
    fun `snap-back needs a known canvas size`() {
        assertFalse(StrokeSelection.shouldSnapBack(LiveTransform(dx = 1f), 0f, 100f, 0.1f))
        assertFalse(StrokeSelection.shouldSnapBack(LiveTransform(dx = 1f), 100f, 0f, 0.1f))
    }

    @Test
    fun `snap-back applies to moves only, never scales`() {
        val scaled = LiveTransform(dx = 1f, dy = 1f, scaleX = 2f, scaleY = 2f)
        assertFalse(StrokeSelection.shouldSnapBack(scaled, 100f, 100f, 0.5f))
    }

    @Test
    fun `snap-back normalises each axis by its own canvas dimension`() {
        // W=200,H=100: a (10,5) move = sqrt(0.05² + 0.05²) = 0.0707 < 0.1.
        assertTrue(StrokeSelection.shouldSnapBack(LiveTransform(dx = 10f, dy = 5f), 200f, 100f, 0.1f))
    }

    // --- FA-10 ghost gating ---

    @Test
    fun `no ghost when nothing is selected`() {
        assertFalse(StrokeSelection.shouldShowGhost(null))
    }

    @Test
    fun `no ghost when a selection is idle (not being moved)`() {
        val sel = SelectionState(ids = setOf("a"), bounds = SelectionBounds(0f, 0f, 10f, 10f))
        assertFalse(StrokeSelection.shouldShowGhost(sel))
    }

    @Test
    fun `ghost shows while a selection is being moved`() {
        val sel = SelectionState(
            ids = setOf("a"),
            bounds = SelectionBounds(0f, 0f, 10f, 10f),
            transform = LiveTransform(dx = 5f),
        )
        assertTrue(StrokeSelection.shouldShowGhost(sel))
    }
}
