package ai.elrond.data

import ai.elrond.domain.RecognizedLine
import ai.elrond.domain.AutoExtractionRunner
import ai.elrond.domain.groupStrokesIntoLines
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * True for strokes that carry handwriting the AI should read (FA-23): highlighter marks are
 * emphasis over writing, not writing — feeding them to ML Kit garbles the recognized lines.
 * Pen and pencil strokes stay recognizable.
 */
fun isRecognizableInk(stroke: Stroke): Boolean = stroke.brush.family != StockBrushes.highlighter()

/**
 * Turns a page's strokes into [RecognizedLine]s (text + canvas-pixel bounds) — the one
 * ink/ML-Kit-touching step of background extraction, kept out of [AutoExtractionRunner]
 * so the runner stays pure-JVM testable. Empty (unrecognized) lines are dropped, and
 * highlighter strokes are excluded up front ([isRecognizableInk]).
 */
suspend fun buildRecognizedLines(
    strokes: List<Stroke>,
    recognizer: HandwritingRecognizer,
): List<RecognizedLine> {
    val recognizable = strokes.filter(::isRecognizableInk)
    if (recognizable.isEmpty()) return emptyList()
    return groupStrokesIntoLines(recognizable).mapNotNull { line ->
        val text = recognizer.recognize(line).getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return@mapNotNull null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        val scratch = StrokeInput()
        line.forEach { stroke ->
            for (i in 0 until stroke.inputs.size) {
                stroke.inputs.populate(i, scratch)
                if (scratch.x < minX) minX = scratch.x
                if (scratch.x > maxX) maxX = scratch.x
                if (scratch.y < minY) minY = scratch.y
                if (scratch.y > maxY) maxY = scratch.y
            }
        }
        RecognizedLine(text = text, minX = minX, minY = minY, maxX = maxX, maxY = maxY)
    }
}
