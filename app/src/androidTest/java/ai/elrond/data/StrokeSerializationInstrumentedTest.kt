package ai.elrond.data

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
 * Since the FA-23 upgrade to ink 1.0.0 the load path relies on [StrokeInputSanitizer], so the
 * adversarial fixtures here are the merge gate: stored garbage must reconstruct without throwing.
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

    /**
     * FA-22 progressive load: [NoteRepository.loadStrokesProgressive] rebuilds stroke meshes in
     * PARALLEL chunks on [Dispatchers.Default]. The chunking/order logic is JVM-tested; what only a
     * device can prove is that concurrent ink-native mesh construction is safe and lossless. This
     * mirrors the repository's exact fan-out shape.
     */
    @Test
    fun parallelChunkedReconstruction_isLosslessAndOrdered() = runBlocking {
        val entities = (0 until 200).map { i ->
            StrokeSerialization.toEntity(
                stroke = buildStroke(POINTS.map { (x, y) -> x + i to y + i }),
                id = "s$i",
                pageId = "page-1",
                createdAt = i.toLong(),
            )
        }

        val rebuilt = entities.chunked(16).map { chunk ->
            async(Dispatchers.Default) { chunk.map { StrokeSerialization.toCanvasStroke(it) } }
        }.awaitAll().flatten()

        assertEquals(entities.map { it.id }, rebuilt.map { it.id })
        rebuilt.forEach { cs ->
            assertEquals("stroke ${cs.id} lost points under parallel rebuild", POINTS.size, cs.stroke.inputs.size)
        }
    }

    /**
     * FA-23 (ink 1.0.0) merge gate: adversarial stored payloads — duplicate and decreasing
     * timestamps, NaN coords, out-of-range pressure — must reconstruct via the sanitizer without
     * throwing and without collapsing to a dot.
     */
    @Test
    fun adversarialStoredPoints_reconstructSanitized() {
        val dirty = listOf(
            SerializedStrokeInput(x = 10f, y = 10f, t = 0L, pressure = 0.5f, tilt = 0.1f, orientation = 0.2f),
            SerializedStrokeInput(x = 20f, y = 18f, t = 0L, pressure = 0.5f, tilt = 0.1f, orientation = 0.2f), // dup t
            SerializedStrokeInput(x = 35f, y = 30f, t = -5L, pressure = 1.7f, tilt = 0.1f, orientation = 0.2f), // decreasing t, bad pressure
            SerializedStrokeInput(x = Float.NaN, y = 40f, t = 30L, pressure = 0.5f, tilt = 0.1f, orientation = 0.2f), // NaN x — dropped
            // NaN pressure: a mixed reported/unreported batch throws in ink 1.0.0 unless the gap
            // is filled (all-or-none rule) — plus out-of-range tilt/orientation that must clamp.
            SerializedStrokeInput(x = 52f, y = 41f, t = 30L, pressure = Float.NaN, tilt = 9f, orientation = -3f),
            SerializedStrokeInput(x = 70f, y = 49f, t = 40L, pressure = 0.9f, tilt = 0.1f, orientation = 0.2f),
        )
        val entity = StrokeEntity(
            id = "dirty-1",
            pageId = "page-1",
            brushFamily = "pressure-pen",
            colorArgb = USER_INK_COLOR,
            brushSize = 5f,
            brushEpsilon = 0.1f,
            inputs = StrokeSerialization.encodeInputs(dirty),
            createdAt = 0L,
            isAiInk = false,
            groupId = null,
        )

        val restored = StrokeSerialization.toStroke(entity) // must not throw

        // 5 finite points survive (the NaN one is dropped); no collapse-to-dot.
        assertEquals(5, restored.inputs.size)
        val scratch = StrokeInput()
        var prevT = Long.MIN_VALUE
        for (i in 0 until restored.inputs.size) {
            restored.inputs.populate(i, scratch)
            assertTrue("timestamps must be strictly increasing", scratch.elapsedTimeMillis > prevT)
            prevT = scratch.elapsedTimeMillis
        }
    }

    /** Builds a real ink [Stroke] from a list of (x, y) points with strictly-increasing timestamps. */
    private fun buildStroke(points: List<Pair<Float, Float>>): Stroke {
        val batch = MutableStrokeInputBatch()
        points.forEachIndexed { index, (x, y) ->
            batch.add(
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
            family = StockBrushes.pressurePen(),
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
