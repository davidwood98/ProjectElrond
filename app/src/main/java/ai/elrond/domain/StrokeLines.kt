package ai.elrond.domain

import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * Groups canvas strokes into handwriting lines (top-to-bottom) using their
 * vertical extents. Touches ink natives — ViewModel tests inject a fake.
 */
fun groupStrokesIntoLines(strokes: List<Stroke>): List<List<Stroke>> {
    if (strokes.isEmpty()) return emptyList()
    val spans = strokes.map { it.verticalSpan() }
    return StrokeLineGrouper.groupIntoLines(spans).map { indices -> indices.map(strokes::get) }
}

/**
 * Same top-to-bottom line grouping as [groupStrokesIntoLines], but over [CanvasStroke]s so the
 * stable [CanvasStroke.id]s survive into each line — the input to the FA-24b recognition-cache
 * key. Reuses [StrokeLineGrouper.groupIntoLines] on the wrapped ink spans, then maps the grouped
 * indices back to the original [CanvasStroke]s. Touches ink natives (via [lineSpan]).
 */
fun groupCanvasStrokesIntoLines(strokes: List<CanvasStroke>): List<List<CanvasStroke>> {
    if (strokes.isEmpty()) return emptyList()
    val spans = strokes.map { lineSpan(listOf(it.stroke)) }
    return StrokeLineGrouper.groupIntoLines(spans).map { indices -> indices.map(strokes::get) }
}

private fun Stroke.verticalSpan(): StrokeLineGrouper.Span = lineSpan(listOf(this))

/** Vertical extent (min/max Y) of an entire handwriting line. Touches ink natives. */
fun lineSpan(line: List<Stroke>): StrokeLineGrouper.Span {
    val scratch = StrokeInput()
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    line.forEach { stroke ->
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            if (scratch.y < minY) minY = scratch.y
            if (scratch.y > maxY) maxY = scratch.y
        }
    }
    return StrokeLineGrouper.Span(minY, maxY)
}

/**
 * Default multi-line question selection for a bare trigger: the contiguous block of lines
 * directly above [triggerIndex] (stopping at a paragraph gap). Touches ink natives — the
 * ViewModel injects a fake in tests.
 */
fun selectQuestionLines(lines: List<List<Stroke>>, triggerIndex: Int): List<Int> =
    StrokeLineGrouper.blockAbove(lines.map(::lineSpan), triggerIndex)

/** All of a stroke's input points as a polyline (canvas px). Touches ink natives. */
fun strokePolyline(stroke: Stroke): List<GestureTriggerDetector.Point> {
    val scratch = StrokeInput()
    val points = ArrayList<GestureTriggerDetector.Point>(stroke.inputs.size)
    for (i in 0 until stroke.inputs.size) {
        stroke.inputs.populate(i, scratch)
        points += GestureTriggerDetector.Point(scratch.x, scratch.y)
    }
    return points
}

/** The stroke's polyline if it forms a lasso (closed loop), else null. */
fun strokeLoopOrNull(stroke: Stroke): List<GestureTriggerDetector.Point>? =
    strokePolyline(stroke).takeIf(GestureTriggerDetector::isLasso)

/** Geometric centroid of a stroke's input points (used to test lasso enclosure). */
fun strokeCentroid(stroke: Stroke): GestureTriggerDetector.Point {
    val points = strokePolyline(stroke)
    if (points.isEmpty()) return GestureTriggerDetector.Point(0f, 0f)
    var sumX = 0f
    var sumY = 0f
    points.forEach { sumX += it.x; sumY += it.y }
    return GestureTriggerDetector.Point(sumX / points.size, sumY / points.size)
}

/**
 * Default placement for an AI response note: just below the trigger line, at the
 * page's left margin (the box then fills the line width). Falls back to a fixed
 * position when there are no strokes.
 */
fun defaultAiNotePosition(triggerLine: List<Stroke>): NotePosition {
    if (triggerLine.isEmpty()) return NotePosition(LEFT_MARGIN_PX, FALLBACK_Y)
    val scratch = StrokeInput()
    var maxY = Float.NEGATIVE_INFINITY
    triggerLine.forEach { stroke ->
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            if (scratch.y > maxY) maxY = scratch.y
        }
    }
    return NotePosition(x = LEFT_MARGIN_PX, y = maxY + NOTE_MARGIN_PX)
}

/** Standard left margin from the page edge for AI response boxes. */
const val LEFT_MARGIN_PX = 32f
private const val NOTE_MARGIN_PX = 48f
private const val FALLBACK_Y = 120f
