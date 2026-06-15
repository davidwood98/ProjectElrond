package ai.elrond.data

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device round-trip test for [StrokeSerialization]. This is the coverage the FA-7 regression
 * slipped through: [StrokeSerialization.toEntity]/[StrokeSerialization.toStroke] touch ink natives
 * (`MutableStrokeInputBatch`, `Stroke`) that don't load under JVM/Robolectric, so the pure-data
 * `SerializedStrokeInputTest` could never see the ink reconstruction.
 *
 * The regression (ink 1.0.0's `add` throwing where alpha04's `addOrIgnore` skipped) collapsed every
 * reloaded multi-point stroke down to a single dot. The size assertion below fails exactly that case.
 */
@RunWith(AndroidJUnit4::class)
class StrokeSerializationInstrumentedTest {

    @Test
    fun multiPointStroke_survivesRoundTrip() {
        val original = buildStroke(POINTS)

        // Sanity: the fixture really is a multi-point stroke (so the size check below is meaningful).
        assertTrue("fixture should have several points", original.inputs.size >= 5)

        val entity = StrokeSerialization.toEntity(
            stroke = original,
            id = "stroke-1",
            pageId = "page-1",
            createdAt = 1_000L,
        )
        val restored = StrokeSerialization.toStroke(entity)

        // The regression guard: a reloaded stroke must keep every point, not collapse to a dot.
        assertEquals(
            "reloaded stroke lost points (collapse-to-dot regression)",
            original.inputs.size,
            restored.inputs.size,
        )

        // Endpoints must match positionally so the stroke renders as the same shape.
        val origFirst = StrokeInput()
        val restFirst = StrokeInput()
        original.inputs.populate(0, origFirst)
        restored.inputs.populate(0, restFirst)
        assertEquals(origFirst.x, restFirst.x, TOLERANCE)
        assertEquals(origFirst.y, restFirst.y, TOLERANCE)

        val last = original.inputs.size - 1
        val origLast = StrokeInput()
        val restLast = StrokeInput()
        original.inputs.populate(last, origLast)
        restored.inputs.populate(last, restLast)
        assertEquals(origLast.x, restLast.x, TOLERANCE)
        assertEquals(origLast.y, restLast.y, TOLERANCE)
    }

    @Test
    fun brushParameters_roundTrip() {
        val original = buildStroke(POINTS)
        val entity = StrokeSerialization.toEntity(original, "stroke-2", "page-1", 2_000L)

        assertEquals("pressure-pen", entity.brushFamily)
        assertEquals(USER_INK_COLOR, entity.colorArgb)

        val restored = StrokeSerialization.toStroke(entity)
        assertEquals(entity.colorArgb, restored.brush.colorIntArgb)
        assertEquals(entity.brushSize, restored.brush.size, TOLERANCE)
    }

    /** Builds a real ink [Stroke] from a list of (x, y) points with strictly-increasing timestamps. */
    private fun buildStroke(points: List<Pair<Float, Float>>): Stroke {
        val batch = MutableStrokeInputBatch()
        points.forEachIndexed { index, (x, y) ->
            batch.addOrIgnore(
                type = InputToolType.STYLUS,
                x = x,
                y = y,
                elapsedTimeMillis = index * 8L, // strictly increasing — all points valid
                pressure = 0.5f,
                tiltRadians = 0.1f,
                orientationRadians = 0.2f,
            )
        }
        val brush = Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePenLatest,
            colorIntArgb = USER_INK_COLOR,
            size = 5f,
            epsilon = 0.1f,
        )
        return Stroke(brush, batch)
    }

    private companion object {
        const val USER_INK_COLOR = 0xFF1A237E.toInt()
        const val TOLERANCE = 0.5f
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
