package ai.elrond.domain

import kotlin.math.hypot
import kotlin.math.roundToLong

/** One stroke input point as pure data — the ink-native tool type is handled by the caller. */
data class InkPoint(
    val x: Float,
    val y: Float,
    val t: Long,
    val pressure: Float = 1f,
    val tilt: Float = 0f,
    val orientation: Float = 0f,
)

/**
 * A non-solid-line stroke while the pen is still down (FA-23). The wet ink layer can't render
 * dash patterns, so InkCanvas buffers the points here and a Compose overlay draws the pattern
 * live; on pen-up the points bake into a real ink stroke and flow through the normal
 * finish/segmentation pipeline.
 */
data class LivePatternStroke(
    val points: List<InkPoint>,
    val spec: BrushSpec,
    val lineType: InkLineType,
)

/**
 * A hold-to-straighten line while the pen is still down (FA-23): after a stationary hold the drawn
 * stroke snaps to this straight line; further movement adjusts the endpoint, lift commits. Rendered
 * by the same Compose overlay as [LivePatternStroke], in the tool's colour and line style.
 */
data class StraightLinePreview(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val spec: BrushSpec,
    val lineType: InkLineType,
)

/**
 * Pure geometry for FA-23 line patterns: walks a drawn polyline by cumulative arc length and cuts
 * it into the dash/dot segments of an [InkLineType]. Each returned segment becomes its own ink
 * stroke (carrying the tool's real brush) sharing a groupId — see `StrokeTransforms.segment` for
 * the ink-native bridge and `CanvasViewModel.onStrokesFinished` for the hook.
 */
object LinePatterning {

    /**
     * Hard ceiling on segments from one stroke (a very long dotted line). Beyond it the remainder
     * of the polyline is emitted as one final solid segment rather than silently dropped.
     */
    const val MAX_PATTERN_SEGMENTS = 400

    /**
     * Cuts [points] into the on-pattern sub-polylines of [lineType] (pattern lengths scale with
     * [brushSize]). Channel values (t / pressure / tilt / orientation) are interpolated at span
     * boundaries so dash ends are exact; each segment's timestamps are rebased to start at 0 and
     * kept strictly increasing, so segments are always valid for ink's throwing `add`.
     *
     * [InkLineType.SOLID], a single point, or a degenerate (zero-length) polyline pass through as
     * one segment.
     */
    fun segmentPolyline(
        points: List<InkPoint>,
        lineType: InkLineType,
        brushSize: Float,
    ): List<List<InkPoint>> {
        val runs = lineType.patternRuns
        if (runs.isEmpty() || points.size < 2) return listOf(points).map(::rebase)

        // The pattern as a repeating list of (isDraw, length) intervals; a zero-length draw is a dot.
        data class Interval(val draw: Boolean, val length: Float)
        val pattern = buildList {
            runs.forEach { run ->
                add(Interval(draw = true, length = run.drawLen * brushSize))
                add(Interval(draw = false, length = run.gapLen * brushSize))
            }
        }
        if (pattern.all { it.length <= 0f }) return listOf(rebase(points))

        val segments = mutableListOf<List<InkPoint>>()
        var current = mutableListOf<InkPoint>()
        var intervalIdx = -1
        var remaining = 0f
        var capped = false

        fun interval() = pattern[intervalIdx % pattern.size]

        fun closeSegment() {
            if (current.isNotEmpty()) segments.add(rebase(current))
            current = mutableListOf()
        }

        /**
         * Enters the next positive-length interval starting at [at]; zero-length draw intervals
         * become single-point dot segments in passing (zero-length gaps are skipped). Entering a
         * draw interval seeds the new segment with [at].
         */
        fun advanceInterval(at: InkPoint) {
            while (true) {
                intervalIdx++
                val iv = interval()
                if (iv.length <= 0f) {
                    if (iv.draw && segments.size < MAX_PATTERN_SEGMENTS) {
                        segments.add(listOf(at.copy(t = 0L)))
                    }
                    continue
                }
                remaining = iv.length
                if (iv.draw) current.add(at)
                return
            }
        }

        advanceInterval(points[0])

        var prev = points[0]
        var i = 1
        while (i < points.size) {
            if (segments.size >= MAX_PATTERN_SEGMENTS) {
                // Cap hit: finish the remainder as one solid tail so nothing is dropped.
                capped = true
                break
            }
            val next = points[i]
            val edgeLen = hypot((next.x - prev.x).toDouble(), (next.y - prev.y).toDouble()).toFloat()
            if (edgeLen <= 0f) {
                i++
                continue
            }
            if (edgeLen < remaining) {
                remaining -= edgeLen
                if (interval().draw) current.add(next)
                prev = next
                i++
            } else {
                // The interval boundary falls on this edge: split at the interpolated point and
                // stay on the edge (prev moves to the boundary; i is not advanced).
                val boundary = lerp(prev, next, remaining / edgeLen)
                if (interval().draw) {
                    current.add(boundary)
                    closeSegment()
                }
                advanceInterval(boundary)
                prev = boundary
            }
        }
        if (capped) {
            closeSegment() // whatever dash was mid-draw
            val tail = mutableListOf(prev)
            for (j in i until points.size) tail.add(points[j])
            segments.add(rebase(tail))
        } else if (interval().draw && current.size > 1) {
            // Close the final dash — unless it "started" exactly at the line's end (a zero-length
            // dash born on the last boundary), which would render as a stray dot.
            closeSegment()
        }
        return segments.filter { it.isNotEmpty() }
    }

    /**
     * Makes live-captured points valid for ink 1.0.0's throwing `add`: drops non-finite and
     * consecutive-coincident positions and forces strictly-increasing timestamps. Raw MotionEvent
     * capture can repeat a point — the DOWN coordinates arrive again as the first historical MOVE
     * sample with the same eventTime — and `MutableStrokeInputBatch.add` rejects a duplicate
     * (position, elapsed_time) pair outright (the FA-23 dashed-stroke crash). The stored-points
     * path has its own guard (`StrokeInputSanitizer`); this is the live-capture equivalent, applied
     * in `StrokeTransforms.buildStroke`.
     */
    fun sanitizeForInk(points: List<InkPoint>): List<InkPoint> {
        if (points.isEmpty()) return points
        val out = ArrayList<InkPoint>(points.size)
        var prev: InkPoint? = null
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            val last = prev
            if (last != null && p.x == last.x && p.y == last.y) continue // coincident: adds nothing
            val t = if (last == null) maxOf(p.t, 0L) else maxOf(p.t, last.t + 1)
            val q = if (t == p.t) p else p.copy(t = t)
            out.add(q)
            prev = q
        }
        return out
    }

    /**
     * Evenly-spaced points along the straight line (x1,y1)→(x2,y2) — the committed form of a
     * hold-to-straighten stroke (FA-23). Constant [pressure], timestamps spaced 1ms so the ink
     * batch is always valid. At least the two endpoints are returned.
     */
    fun straightLinePoints(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        spacing: Float,
        pressure: Float = 1f,
    ): List<InkPoint> {
        val length = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        val steps = if (spacing > 0f) (length / spacing).toInt().coerceIn(1, 4096) else 1
        return (0..steps).map { s ->
            val f = s.toFloat() / steps
            InkPoint(
                x = x1 + (x2 - x1) * f,
                y = y1 + (y2 - y1) * f,
                t = s.toLong(),
                pressure = pressure,
            )
        }
    }

    /** Rebases a segment's timestamps to start at 0 and stay strictly increasing. */
    private fun rebase(points: List<InkPoint>): List<InkPoint> {
        if (points.isEmpty()) return points
        val t0 = points.first().t
        var prevT = -1L
        return points.map { p ->
            val t = maxOf(p.t - t0, prevT + 1)
            prevT = t
            p.copy(t = t)
        }
    }

    private fun lerp(a: InkPoint, b: InkPoint, f: Float): InkPoint = InkPoint(
        x = a.x + (b.x - a.x) * f,
        y = a.y + (b.y - a.y) * f,
        t = a.t + ((b.t - a.t) * f).roundToLong(),
        pressure = a.pressure + (b.pressure - a.pressure) * f,
        tilt = a.tilt + (b.tilt - a.tilt) * f,
        orientation = a.orientation + (b.orientation - a.orientation) * f,
    )
}
