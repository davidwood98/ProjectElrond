package ai.elrond.extract

import ai.elrond.domain.RecognizedLine
import ai.elrond.domain.AutoExtractionRunner
import ai.elrond.ai.HandwritingRecognizer
import ai.elrond.domain.groupStrokesIntoLines
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * Turns a page's strokes into [RecognizedLine]s (text + canvas-pixel bounds) — the one
 * ink/ML-Kit-touching step of background extraction, kept out of [AutoExtractionRunner]
 * so the runner stays pure-JVM testable. Empty (unrecognized) lines are dropped.
 */
suspend fun buildRecognizedLines(
    strokes: List<Stroke>,
    recognizer: HandwritingRecognizer,
): List<RecognizedLine> {
    if (strokes.isEmpty()) return emptyList()
    return groupStrokesIntoLines(strokes).mapNotNull { line ->
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
