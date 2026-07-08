package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure NotebookLink model + default-placement geometry (FA-24). */
class NotebookLinkTest {

    private fun link(target: String? = "nb-1") = NotebookLink(
        id = "l1",
        targetNotebookId = target,
        x = 0f,
        y = 0f,
        widthPx = NotebookLink.DEFAULT_WIDTH_PX,
        linkText = "Target",
        createdAt = 0L,
    )

    @Test
    fun `isBroken is true only when the target notebook id is null`() {
        assertFalse(link(target = "nb-1").isBroken)
        assertTrue(link(target = null).isBroken)
    }

    @Test
    fun `default position centres the link box in the viewport, in page space`() {
        // Identity-ish transform: scale 1, no offsets — page space == screen space.
        val t = PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val pos = defaultLinkPosition(t, canvasWidthPx = 1000f, canvasHeightPx = 800f)
        assertEquals(500f - NotebookLink.DEFAULT_WIDTH_PX / 2f, pos.x, 0.001f)
        assertEquals(400f, pos.y, 0.001f)
    }

    @Test
    fun `default position accounts for zoom and scroll`() {
        // Zoomed 2x, scrolled down 100px: the screen centre maps back through the transform.
        val t = PageTransform(scale = 2f, offsetX = 50f, offsetY = -100f)
        val pos = defaultLinkPosition(t, canvasWidthPx = 1000f, canvasHeightPx = 800f)
        assertEquals(t.screenToPageX(500f) - NotebookLink.DEFAULT_WIDTH_PX / 2f, pos.x, 0.001f)
        assertEquals(t.screenToPageY(400f), pos.y, 0.001f)
    }

    @Test
    fun `default position clamps to the page origin`() {
        // A viewport centre near the page's left edge must not push the box off-page.
        val t = PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val pos = defaultLinkPosition(t, canvasWidthPx = 100f, canvasHeightPx = 100f)
        assertTrue(pos.x >= 0f)
        assertTrue(pos.y >= 0f)
    }

    @Test
    fun `unknown canvas size falls back to the page origin`() {
        val t = PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val pos = defaultLinkPosition(t, canvasWidthPx = 0f, canvasHeightPx = 0f)
        assertEquals(0f, pos.x, 0f)
        assertEquals(0f, pos.y, 0f)
    }
}
