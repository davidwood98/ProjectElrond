package ai.elrond.ai

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

private fun Stroke.verticalSpan(): StrokeLineGrouper.Span {
    val scratch = StrokeInput()
    var minY = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    for (i in 0 until inputs.size) {
        inputs.populate(i, scratch)
        if (scratch.y < minY) minY = scratch.y
        if (scratch.y > maxY) maxY = scratch.y
    }
    return StrokeLineGrouper.Span(minY, maxY)
}

/**
 * Default placement for an AI response note: just below the trigger line,
 * left-aligned with it. Falls back to a fixed position with no strokes.
 */
fun defaultAiNotePosition(triggerLine: List<Stroke>): NotePosition {
    if (triggerLine.isEmpty()) return NotePosition(FALLBACK_X, FALLBACK_Y)
    val scratch = StrokeInput()
    var minX = Float.POSITIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    triggerLine.forEach { stroke ->
        for (i in 0 until stroke.inputs.size) {
            stroke.inputs.populate(i, scratch)
            if (scratch.x < minX) minX = scratch.x
            if (scratch.y > maxY) maxY = scratch.y
        }
    }
    return NotePosition(x = minX, y = maxY + NOTE_MARGIN_PX)
}

private const val NOTE_MARGIN_PX = 48f
private const val FALLBACK_X = 120f
private const val FALLBACK_Y = 120f
