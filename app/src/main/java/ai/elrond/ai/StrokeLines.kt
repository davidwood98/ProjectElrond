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
