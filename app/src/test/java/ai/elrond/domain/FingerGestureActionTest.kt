package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FA-19: parsing for the finger-gesture action enum + the stylus-hold tool. */
class FingerGestureActionTest {

    @Test
    fun `fromName round-trips every value`() {
        FingerGestureAction.entries.forEach { action ->
            assertEquals(action, FingerGestureAction.fromName(action.name))
        }
    }

    @Test
    fun `fromName falls back to NONE for null or unknown`() {
        assertEquals(FingerGestureAction.NONE, FingerGestureAction.fromName(null))
        assertEquals(FingerGestureAction.NONE, FingerGestureAction.fromName("nonsense"))
        assertEquals(FingerGestureAction.NONE, FingerGestureAction.fromName(""))
    }

    @Test
    fun `StylusHoldTool defaults to Eraser and round-trips`() {
        assertEquals(StylusHoldTool.ERASER, StylusHoldTool.DEFAULT)
        StylusHoldTool.entries.forEach { tool ->
            assertEquals(tool, StylusHoldTool.fromName(tool.name))
        }
        assertEquals(StylusHoldTool.ERASER, StylusHoldTool.fromName(null))
        assertEquals(StylusHoldTool.ERASER, StylusHoldTool.fromName("nonsense"))
    }

    @Test
    fun `StylusHoldTool maps to a canvas tool, NONE to null`() {
        assertNull(StylusHoldTool.NONE.toCanvasTool())
        assertEquals(CanvasTool.PEN, StylusHoldTool.PEN.toCanvasTool())
        assertEquals(CanvasTool.ERASER, StylusHoldTool.ERASER.toCanvasTool())
        assertEquals(CanvasTool.LASSO, StylusHoldTool.LASSO.toCanvasTool())
    }
}
