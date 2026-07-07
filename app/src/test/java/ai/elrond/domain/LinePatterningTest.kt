package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** FA-23 pure pattern geometry: arc-length segmentation + straight-line synthesis. */
class LinePatterningTest {

    /** A horizontal polyline from x=0 to [length], one point every [step]. */
    private fun horizontal(length: Float, step: Float = 10f): List<InkPoint> {
        val points = mutableListOf<InkPoint>()
        var x = 0f
        var t = 0L
        while (x <= length + 0.001f) {
            points.add(InkPoint(x = x, y = 0f, t = t, pressure = 0.5f))
            x += step
            t += 8
        }
        return points
    }

    private fun span(segment: List<InkPoint>): Pair<Float, Float> =
        segment.first().x to segment.last().x

    @Test
    fun `solid passes through as one segment`() {
        val points = horizontal(50f)
        val segments = LinePatterning.segmentPolyline(points, InkLineType.SOLID, 4f)
        assertEquals(1, segments.size)
        assertEquals(points.map { it.x }, segments[0].map { it.x })
    }

    @Test
    fun `a single point passes through untouched`() {
        val points = listOf(InkPoint(5f, 5f, 0L))
        assertEquals(listOf(points), LinePatterning.segmentPolyline(points, InkLineType.DASHED, 4f))
    }

    @Test
    fun `dashed cuts exact 6-on 3-off spans scaled by brush size`() {
        // Brush size 1 → dash 6, gap 3, cycle 9. Line of 90 → dashes at 0..6, 9..15, 18..24, …
        val segments = LinePatterning.segmentPolyline(horizontal(90f), InkLineType.DASHED, 1f)
        assertEquals(10, segments.size)
        segments.forEachIndexed { i, seg ->
            val (start, end) = span(seg)
            assertEquals("dash $i start", i * 9f, start, 0.01f)
            assertEquals("dash $i end", i * 9f + 6f, end, 0.01f)
        }
    }

    @Test
    fun `dash boundaries interpolate channel values`() {
        // Two points only — every dash boundary is interpolated. Pressure ramps 0 → 1 over x 0 → 90.
        val points = listOf(
            InkPoint(0f, 0f, 0L, pressure = 0f),
            InkPoint(90f, 0f, 900L, pressure = 1f),
        )
        val segments = LinePatterning.segmentPolyline(points, InkLineType.DASHED, 1f)
        val secondDash = segments[1] // spans x 9..15
        assertEquals(9f, secondDash.first().x, 0.01f)
        assertEquals(0.1f, secondDash.first().pressure, 0.01f)
        assertEquals(15f, secondDash.last().x, 0.01f)
    }

    @Test
    fun `dotted emits single-point dots at the gap spacing`() {
        // Brush size 2 → gap 5. Line of 20 → dots at 0, 5, 10, 15, 20.
        val segments = LinePatterning.segmentPolyline(horizontal(20f, step = 5f), InkLineType.DOTTED, 2f)
        assertEquals(5, segments.size)
        segments.forEachIndexed { i, seg ->
            assertEquals("dot $i is a single point", 1, seg.size)
            assertEquals(i * 5f, seg[0].x, 0.01f)
        }
    }

    @Test
    fun `centreline alternates long and short dashes`() {
        // Brush size 1 → long 12, gap 3, short 3, gap 3 (cycle 21). Line of 42 → L S L S.
        val segments = LinePatterning.segmentPolyline(horizontal(42f, step = 1f), InkLineType.CENTRELINE, 1f)
        assertEquals(4, segments.size)
        val lengths = segments.map { seg -> span(seg).let { it.second - it.first } }
        assertEquals(12f, lengths[0], 0.05f)
        assertEquals(3f, lengths[1], 0.05f)
        assertEquals(12f, lengths[2], 0.05f)
        assertEquals(3f, lengths[3], 0.05f)
    }

    @Test
    fun `dash-dot alternates dashes and dots`() {
        // Brush size 1 → dash 8, gap 3, dot, gap 3 (cycle 14). Line of 28 → dash dot dash dot.
        val segments = LinePatterning.segmentPolyline(horizontal(28f, step = 1f), InkLineType.DASH_DOT, 1f)
        assertEquals(4, segments.size)
        assertTrue(segments[0].size > 1)
        assertEquals(1, segments[1].size)
        assertTrue(segments[2].size > 1)
        assertEquals(1, segments[3].size)
        assertEquals(11f, segments[1][0].x, 0.01f) // dot at dash(8) + gap(3)
    }

    @Test
    fun `segment timestamps rebase to zero and stay strictly increasing`() {
        val segments = LinePatterning.segmentPolyline(horizontal(90f, step = 1f), InkLineType.DASHED, 1f)
        segments.forEach { seg ->
            assertEquals(0L, seg.first().t)
            seg.zipWithNext().forEach { (a, b) -> assertTrue("t must increase", b.t > a.t) }
        }
    }

    @Test
    fun `segment cap emits the remainder as one solid tail`() {
        // Brush 1 → dots every 2.5; a 3000-long line would be 1200 dots — capped at 400 + tail.
        val segments = LinePatterning.segmentPolyline(horizontal(3000f, step = 2f), InkLineType.DOTTED, 1f)
        assertEquals(LinePatterning.MAX_PATTERN_SEGMENTS + 1, segments.size)
        val tail = segments.last()
        assertTrue("tail must be a polyline, not a dot", tail.size > 1)
        assertEquals(3000f, tail.last().x, 0.01f)
        // The tail resumes where the pattern stopped: total coverage has no gap at the cap point.
        assertTrue(tail.first().x <= LinePatterning.MAX_PATTERN_SEGMENTS * 2.5f + 0.01f)
    }

    @Test
    fun `straight line points are evenly spaced with exact endpoints`() {
        val points = LinePatterning.straightLinePoints(10f, 20f, 110f, 20f, spacing = 10f)
        assertEquals(11, points.size)
        assertEquals(10f, points.first().x, 0f)
        assertEquals(110f, points.last().x, 0f)
        points.zipWithNext().forEach { (a, b) ->
            assertEquals(10f, abs(b.x - a.x), 0.01f)
            assertTrue(b.t > a.t)
        }
    }

    @Test
    fun `straight line degenerates to the two endpoints when spacing exceeds length`() {
        val points = LinePatterning.straightLinePoints(0f, 0f, 4f, 0f, spacing = 10f)
        assertEquals(2, points.size)
        assertEquals(0f, points.first().x, 0f)
        assertEquals(4f, points.last().x, 0f)
    }

    // ── sanitizeForInk (the FA-23 dashed-stroke crash: raw capture repeats the DOWN point) ──

    @Test
    fun `sanitize drops a repeated down point`() {
        // The exact crash shape: the DOWN coordinates arrive again as the first historical MOVE
        // sample with the same eventTime — a duplicate (position, elapsed_time) pair.
        val points = listOf(
            InkPoint(746.289f, 1223.95f, 0L),
            InkPoint(746.289f, 1223.95f, 0L),
            InkPoint(750f, 1226f, 8L),
        )
        val clean = LinePatterning.sanitizeForInk(points)
        assertEquals(2, clean.size)
        assertEquals(746.289f, clean[0].x, 0f)
        assertEquals(750f, clean[1].x, 0f)
    }

    @Test
    fun `sanitize forces strictly increasing timestamps`() {
        val points = listOf(
            InkPoint(0f, 0f, 5L),
            InkPoint(1f, 0f, 5L), // same eventTime batch
            InkPoint(2f, 0f, 4L), // out of order
            InkPoint(3f, 0f, 20L),
        )
        val clean = LinePatterning.sanitizeForInk(points)
        assertEquals(4, clean.size)
        clean.zipWithNext().forEach { (a, b) -> assertTrue("t must strictly increase", b.t > a.t) }
        assertEquals(20L, clean.last().t)
    }

    @Test
    fun `sanitize drops non-finite coordinates and coincident followers`() {
        val points = listOf(
            InkPoint(0f, 0f, 0L),
            InkPoint(Float.NaN, 1f, 1L),
            InkPoint(0f, 0f, 2L), // coincident with the first accepted point
            InkPoint(5f, 5f, 3L),
        )
        val clean = LinePatterning.sanitizeForInk(points)
        assertEquals(2, clean.size)
        assertEquals(5f, clean.last().x, 0f)
    }

    @Test
    fun `sanitize clamps a negative first timestamp to zero`() {
        val clean = LinePatterning.sanitizeForInk(listOf(InkPoint(0f, 0f, -3L), InkPoint(1f, 1f, -2L)))
        assertEquals(0L, clean.first().t)
        assertTrue(clean[1].t > clean[0].t)
    }

    @Test
    fun `sanitize clamps device pressure into range but keeps it reported`() {
        // Some devices report MotionEvent pressure > 1; pressure must survive, clamped not dropped.
        val clean = LinePatterning.sanitizeForInk(
            listOf(
                InkPoint(0f, 0f, 0L, pressure = 1.4f),
                InkPoint(1f, 0f, 8L, pressure = Float.NaN),
                InkPoint(2f, 0f, 16L, pressure = -0.2f),
            ),
        )
        assertEquals(listOf(1f, 1f, 0f), clean.map { it.pressure })
    }
}
