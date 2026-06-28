package ai.elrond.domain

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

class PageTransformTest {

    @Test
    fun logical_page_is_portrait_a_ratio() {
        assertEquals(sqrt(2f), PageTransform.ASPECT_RATIO, 1e-6f)
        assertEquals(PageTransform.LOGICAL_WIDTH * sqrt(2f), PageTransform.LOGICAL_HEIGHT, 1e-3f)
    }

    @Test
    fun fitToWidth_scales_logical_width_to_screen_width() {
        val t = PageTransform.fitToWidth(screenWidth = 2000f)
        assertEquals(2000f / PageTransform.LOGICAL_WIDTH, t.scale, 1e-6f)
        // Logical x=0 maps to screen 0; the logical right edge maps to the full screen width.
        assertEquals(0f, t.pageToScreenX(0f), 1e-4f)
        assertEquals(2000f, t.pageToScreenX(PageTransform.LOGICAL_WIDTH), 1e-3f)
    }

    @Test
    fun fitToWidth_origin_offsets_the_page() {
        // scale = 1.0 at this width, so screen = logical + origin.
        val t = PageTransform.fitToWidth(screenWidth = PageTransform.LOGICAL_WIDTH, originX = 50f, originY = -300f)
        assertEquals(150f, t.pageToScreenX(100f), 1e-4f)
        assertEquals(-100f, t.pageToScreenY(200f), 1e-4f)
    }

    @Test
    fun screen_to_page_is_the_inverse_of_page_to_screen() {
        val t = PageTransform(scale = 1.7f, offsetX = 40f, offsetY = -120f)
        val x = 321.5f
        val y = 654.25f
        assertEquals(x, t.screenToPageX(t.pageToScreenX(x)), 1e-3f)
        assertEquals(y, t.screenToPageY(t.pageToScreenY(y)), 1e-3f)
    }

    @Test
    fun length_conversions_use_scale_only_ignoring_offset() {
        val t = PageTransform(scale = 2f, offsetX = 999f, offsetY = -50f)
        assertEquals(20f, t.pageToScreenLength(10f), 1e-4f)
        assertEquals(10f, t.screenToPageLength(20f), 1e-4f)
    }

    @Test
    fun pageScreenHeight_matches_a_ratio_at_fit_to_width() {
        val screenWidth = 1500f
        // height = (sw / W) * (W * √2) = sw * √2
        assertEquals(screenWidth * PageTransform.ASPECT_RATIO, PageTransform.pageScreenHeight(screenWidth), 1e-2f)
    }
}
