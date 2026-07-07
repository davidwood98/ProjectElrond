package ai.elrond.domain

import androidx.ink.brush.Brush
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * Ink-native stroke transforms for the lasso tool: move / scale, clone (duplicate & paste), and
 * per-stroke bounds. Mirrors [ai.elrond.data.StrokeSerialization.toStroke] — an immutable [Stroke]
 * is "transformed" by reading its inputs, remapping x/y, and rebuilding a new `Stroke` from a
 * fresh [MutableStrokeInputBatch] (scaling the brush size with the geometry). Inputs come from a
 * live stroke, so they're already valid for `add` (ink 1.0.0 removed `addOrIgnore`); only the
 * persistence path needs sanitising (see `StrokeInputSanitizer` / CLAUDE.md FA-7/8).
 *
 * These touch ink natives, so they're injected into [CanvasViewModel] as seams (JVM unit tests use
 * fakes) and verified on-device by `StrokeTransformsInstrumentedTest`.
 */
object StrokeTransforms {

    /** Bakes [t] into [stroke], returning a new stroke (or the same one when [t] is identity). */
    fun transformStroke(stroke: Stroke, t: LiveTransform): Stroke {
        if (t.isIdentity) return stroke
        val batch = MutableStrokeInputBatch()
        val scratch = StrokeInput()
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            batch.add(
                type = scratch.toolType,
                x = t.applyX(scratch.x),
                y = t.applyY(scratch.y),
                elapsedTimeMillis = scratch.elapsedTimeMillis,
                pressure = scratch.pressure,
                tiltRadians = scratch.tiltRadians,
                orientationRadians = scratch.orientationRadians,
            )
        }
        val scale = t.brushScale
        val brush = if (scale == 1f) {
            stroke.brush
        } else {
            Brush.createWithColorIntArgb(
                family = stroke.brush.family,
                colorIntArgb = stroke.brush.colorIntArgb,
                size = (stroke.brush.size * scale).coerceAtLeast(MIN_BRUSH_SIZE),
                epsilon = stroke.brush.epsilon,
            )
        }
        return Stroke(brush, batch)
    }

    /** A copy of [stroke] shifted by ([dx], [dy]) — used by duplicate and paste. */
    fun cloneStroke(stroke: Stroke, dx: Float, dy: Float): Stroke =
        transformStroke(stroke, LiveTransform(dx = dx, dy = dy))

    /**
     * A copy of [stroke] re-coloured to [colorIntArgb] with its geometry unchanged (FA-10). Used to
     * draw the faded translucent "ghost" of a lasso selection at its original position during a
     * move — the brush family/size/epsilon and all input points are preserved (the immutable input
     * batch is reused), only the colour (and its alpha) change. Built once per gesture, never per
     * frame.
     */
    fun recolorStroke(stroke: Stroke, colorIntArgb: Int): Stroke {
        val brush = Brush.createWithColorIntArgb(
            family = stroke.brush.family,
            colorIntArgb = colorIntArgb,
            size = stroke.brush.size,
            epsilon = stroke.brush.epsilon,
        )
        return Stroke(brush, stroke.inputs)
    }

    /**
     * Returns a copy of [stroke] with its input points decimated to [minSpacing] page-units apart
     * (debug perf knob — fewer points ⇒ cheaper mesh build + redraw). The brush is unchanged; only
     * the point set is thinned (endpoints always kept). [minSpacing] <= 0, or a stroke already at
     * its minimum (≤2 points / nothing dropped), returns the same stroke untouched.
     *
     * Which points to keep is the pure [StrokeSimplifier.keptIndices]; this just rebuilds the ink
     * batch from them, mirroring [transformStroke] / `StrokeSerialization.toStroke`.
     */
    fun simplify(stroke: Stroke, minSpacing: Float): Stroke {
        if (minSpacing <= 0f || stroke.inputs.size <= 2) return stroke
        val scratch = StrokeInput()
        val points = ArrayList<Pair<Float, Float>>(stroke.inputs.size)
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            points.add(scratch.x to scratch.y)
        }
        val keep = StrokeSimplifier.keptIndices(points, minSpacing)
        if (keep.size == stroke.inputs.size) return stroke // nothing dropped
        val batch = MutableStrokeInputBatch()
        for (i in keep) {
            stroke.inputs.populate(i, scratch)
            batch.add(
                type = scratch.toolType,
                x = scratch.x,
                y = scratch.y,
                elapsedTimeMillis = scratch.elapsedTimeMillis,
                pressure = scratch.pressure,
                tiltRadians = scratch.tiltRadians,
                orientationRadians = scratch.orientationRadians,
            )
        }
        return Stroke(stroke.brush, batch)
    }

    /**
     * Cuts [stroke] into its [lineType] dash/dot segment strokes (FA-23), each carrying the same
     * brush — so a dashed pencil line keeps its texture. Geometry is the pure
     * [LinePatterning.segmentPolyline]; this just reads the inputs and rebuilds each segment,
     * mirroring [transformStroke]. SOLID (or a stroke too short to cut) returns the stroke as-is.
     */
    fun segment(stroke: Stroke, lineType: InkLineType): List<Stroke> {
        if (lineType == InkLineType.SOLID || stroke.inputs.size == 0) return listOf(stroke)
        val scratch = StrokeInput()
        val points = ArrayList<InkPoint>(stroke.inputs.size)
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            points.add(
                InkPoint(
                    x = scratch.x,
                    y = scratch.y,
                    t = scratch.elapsedTimeMillis,
                    pressure = scratch.pressure,
                    tilt = scratch.tiltRadians,
                    orientation = scratch.orientationRadians,
                ),
            )
        }
        stroke.inputs.populate(0, scratch)
        val toolType = scratch.toolType
        val parts = LinePatterning.segmentPolyline(points, lineType, stroke.brush.size)
        if (parts.size == 1) return listOf(stroke)
        return parts.map { part ->
            val batch = MutableStrokeInputBatch()
            part.forEach { p ->
                batch.add(
                    type = toolType,
                    x = p.x,
                    y = p.y,
                    elapsedTimeMillis = p.t,
                    pressure = p.pressure,
                    tiltRadians = p.tilt,
                    orientationRadians = p.orientation,
                )
            }
            Stroke(stroke.brush, batch)
        }
    }

    /**
     * Builds an ink stroke from pure [InkPoint]s with the given brush (FA-23) — the committed form
     * of a live-preview pattern stroke or a hold-to-straighten line. The points are live-captured
     * (raw MotionEvent samples), so they pass through [LinePatterning.sanitizeForInk] first —
     * `add` throws on a duplicate (position, time) pair, which raw capture can produce.
     */
    fun buildStroke(brush: Brush, points: List<InkPoint>): Stroke {
        val batch = MutableStrokeInputBatch()
        LinePatterning.sanitizeForInk(points).forEach { p ->
            batch.add(
                type = androidx.ink.brush.InputToolType.STYLUS,
                x = p.x,
                y = p.y,
                elapsedTimeMillis = p.t,
                pressure = p.pressure,
                tiltRadians = p.tilt,
                orientationRadians = p.orientation,
            )
        }
        return Stroke(brush, batch)
    }

    /** Axis-aligned bounds of a stroke's input points; a zero box when it has none. */
    fun strokeBounds(stroke: Stroke): SelectionBounds {
        val scratch = StrokeInput()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            if (scratch.x < minX) minX = scratch.x
            if (scratch.x > maxX) maxX = scratch.x
            if (scratch.y < minY) minY = scratch.y
            if (scratch.y > maxY) maxY = scratch.y
        }
        return if (minX > maxX) SelectionBounds(0f, 0f, 0f, 0f) else SelectionBounds(minX, minY, maxX, maxY)
    }

    private const val MIN_BRUSH_SIZE = 0.5f
}
