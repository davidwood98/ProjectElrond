package ai.elrond.canvas

import ai.elrond.domain.StrokeTransforms
import ai.elrond.domain.LiveTransform
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [StrokeTransforms] (the lasso tool's move/scale/clone/bounds). These touch
 * ink natives (`MutableStrokeInputBatch`, `Stroke`) that don't load under JVM/Robolectric — so the
 * pure-data [StrokeSelectionTest] covers the transform *math* and this covers the real ink
 * reconstruction (no point loss, correct bounds), mirroring `StrokeSerializationInstrumentedTest`.
 */
@RunWith(AndroidJUnit4::class)
class StrokeTransformsInstrumentedTest {

    @Test
    fun translate_preservesEveryPoint_andShiftsBounds() {
        val original = buildStroke()
        val moved = StrokeTransforms.transformStroke(original, LiveTransform(dx = 100f, dy = 50f))

        assertEquals(original.inputs.size, moved.inputs.size)
        val before = StrokeTransforms.strokeBounds(original)
        val after = StrokeTransforms.strokeBounds(moved)
        assertEquals(before.left + 100f, after.left, TOLERANCE)
        assertEquals(before.top + 50f, after.top, TOLERANCE)
        assertEquals(before.width, after.width, TOLERANCE) // a move doesn't resize
    }

    @Test
    fun scale_aboutPivot_growsBoundsAndBrush_keepingPoints() {
        val original = buildStroke()
        val before = StrokeTransforms.strokeBounds(original)
        val scaled = StrokeTransforms.transformStroke(
            original,
            LiveTransform(scaleX = 2f, scaleY = 2f, pivotX = before.left, pivotY = before.top),
        )

        assertEquals(original.inputs.size, scaled.inputs.size)
        val after = StrokeTransforms.strokeBounds(scaled)
        assertEquals(before.width * 2f, after.width, TOLERANCE_WIDE)
        assertEquals(before.height * 2f, after.height, TOLERANCE_WIDE)
        assertTrue("brush size should scale with the geometry", scaled.brush.size > original.brush.size)
    }

    @Test
    fun cloneStroke_keepsAllPoints() {
        val original = buildStroke()
        val clone = StrokeTransforms.cloneStroke(original, dx = 24f, dy = 24f)
        assertEquals(original.inputs.size, clone.inputs.size)
    }

    @Test
    fun recolor_keepsAllPointsAndGeometry_changingOnlyColour() {
        val original = buildStroke()
        val ghostColor = 0x4D1A237E.toInt() // ~30% alpha navy — the FA-10 origin ghost
        val ghost = StrokeTransforms.recolorStroke(original, ghostColor)

        assertEquals(original.inputs.size, ghost.inputs.size)
        assertEquals(ghostColor, ghost.brush.colorIntArgb)
        val before = StrokeTransforms.strokeBounds(original)
        val after = StrokeTransforms.strokeBounds(ghost)
        assertEquals(before.left, after.left, TOLERANCE)
        assertEquals(before.top, after.top, TOLERANCE)
        assertEquals(before.right, after.right, TOLERANCE)
        assertEquals(before.bottom, after.bottom, TOLERANCE)
    }

    @Test
    fun strokeBounds_coversAllInputPoints() {
        val bounds = StrokeTransforms.strokeBounds(buildStroke())
        assertEquals(10f, bounds.left, TOLERANCE)
        assertEquals(10f, bounds.top, TOLERANCE)
        assertEquals(90f, bounds.right, TOLERANCE)
        assertEquals(54f, bounds.bottom, TOLERANCE)
    }

    private fun buildStroke(): Stroke {
        val batch = MutableStrokeInputBatch()
        POINTS.forEachIndexed { index, (x, y) ->
            batch.add(
                type = InputToolType.STYLUS,
                x = x,
                y = y,
                elapsedTimeMillis = index * 8L,
                pressure = 0.5f,
                tiltRadians = 0.1f,
                orientationRadians = 0.2f,
            )
        }
        val brush = Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePen(),
            colorIntArgb = 0xFF1A237E.toInt(),
            size = 5f,
            epsilon = 0.1f,
        )
        return Stroke(brush, batch)
    }

    private companion object {
        const val TOLERANCE = 0.5f
        const val TOLERANCE_WIDE = 1.5f
        val POINTS = listOf(
            10f to 10f,
            20f to 18f,
            35f to 30f,
            52f to 41f,
            70f to 49f,
            90f to 54f,
        )
    }
}
