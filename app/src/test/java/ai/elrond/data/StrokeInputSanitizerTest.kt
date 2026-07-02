package ai.elrond.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM rules of [StrokeInputSanitizer] — the guard that lets `MutableStrokeInputBatch.add`
 * (ink 1.0.0, throws on invalid points) replace alpha04's skip-invalid `addOrIgnore` without
 * re-introducing the FA-7 collapse-to-a-dot regression. The real ink reconstruction is covered
 * on-device by StrokeSerializationInstrumentedTest.
 */
class StrokeInputSanitizerTest {

    private fun point(
        x: Float = 0f,
        y: Float = 0f,
        t: Long = 0L,
        pressure: Float = 0.5f,
        tilt: Float = 0.1f,
        orientation: Float = 0.2f,
    ) = SerializedStrokeInput(x = x, y = y, t = t, pressure = pressure, tilt = tilt, orientation = orientation)

    @Test
    fun `valid monotonic points pass through unchanged`() {
        val points = listOf(point(x = 1f, t = 0), point(x = 2f, t = 8), point(x = 3f, t = 16))
        assertEquals(points, StrokeInputSanitizer.sanitize(points))
    }

    @Test
    fun `duplicate and decreasing timestamps become strictly increasing`() {
        val points = listOf(point(x = 1f, t = 10), point(x = 2f, t = 10), point(x = 3f, t = 4))
        val out = StrokeInputSanitizer.sanitize(points)
        assertEquals(listOf(10L, 11L, 12L), out.map { it.t })
    }

    @Test
    fun `negative first timestamp clamps to zero`() {
        val out = StrokeInputSanitizer.sanitize(listOf(point(x = 1f, t = -5), point(x = 2f, t = -4)))
        assertEquals(0L, out[0].t)
        assertTrue(out[1].t > out[0].t)
    }

    @Test
    fun `non-finite coordinates are dropped`() {
        val points = listOf(
            point(x = 1f, t = 0),
            point(x = Float.NaN, y = 2f, t = 8),
            point(x = 2f, y = Float.POSITIVE_INFINITY, t = 16),
            point(x = 3f, t = 24),
        )
        val out = StrokeInputSanitizer.sanitize(points)
        assertEquals(listOf(1f, 3f), out.map { it.x })
    }

    @Test
    fun `exact-coincident consecutive points are dropped`() {
        val points = listOf(point(x = 1f, y = 1f, t = 0), point(x = 1f, y = 1f, t = 8), point(x = 2f, y = 1f, t = 16))
        val out = StrokeInputSanitizer.sanitize(points)
        assertEquals(2, out.size)
        assertEquals(listOf(1f, 2f), out.map { it.x })
    }

    @Test
    fun `same position later in the stroke is kept when not consecutive`() {
        val points = listOf(point(x = 1f, t = 0), point(x = 2f, t = 8), point(x = 1f, t = 16))
        assertEquals(3, StrokeInputSanitizer.sanitize(points).size)
    }

    @Test
    fun `out-of-range pressure tilt orientation become the unreported sentinel`() {
        val out = StrokeInputSanitizer.sanitize(
            listOf(point(pressure = 1.7f, tilt = 9f, orientation = -3f, x = 1f)),
        )
        assertEquals(-1f, out[0].pressure)
        assertEquals(-1f, out[0].tilt)
        assertEquals(-1f, out[0].orientation)
    }

    @Test
    fun `in-range and sentinel channel values are preserved`() {
        val real = point(x = 1f, pressure = 0.8f, tilt = 0.4f, orientation = 3.1f)
        val out = StrokeInputSanitizer.sanitize(listOf(real))
        assertEquals(real, out[0])
    }

    @Test
    fun `empty input returns empty`() {
        assertTrue(StrokeInputSanitizer.sanitize(emptyList()).isEmpty())
    }
}
