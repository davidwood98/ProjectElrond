package ai.elrond.data

import ai.elrond.domain.AutoExtractionRunner
import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.RecognizedLine
import ai.elrond.domain.groupCanvasStrokesIntoLines
import ai.elrond.domain.recognizedLineKey
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * True for strokes that carry handwriting the AI should read (FA-23): highlighter marks are
 * emphasis over writing, not writing — feeding them to ML Kit garbles the recognized lines.
 * Pen and pencil strokes stay recognizable.
 */
fun isRecognizableInk(stroke: Stroke): Boolean = stroke.brush.family != StockBrushes.highlighter()

/** Canvas-pixel bounds (minX, minY, maxX, maxY) of a handwriting line. Touches ink natives. */
private fun lineBounds(line: List<Stroke>): FloatArray {
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
    return floatArrayOf(minX, minY, maxX, maxY)
}

/**
 * Turns a page's strokes into [RecognizedLine]s (text + canvas-pixel bounds) — the one
 * ink/ML-Kit-touching step of background extraction, kept out of [AutoExtractionRunner]
 * so the runner stays pure-JVM testable — reusing the FA-24b persistent recognition cache
 * so only lines whose stroke-id set changed since the last save pay an ML Kit call.
 *
 * Highlighter strokes are excluded up front ([isRecognizableInk]); empty (unrecognized) lines
 * are dropped from the result and never cached (so a transient blank retries next save). Every
 * line that still exists is upserted with fresh bounds (a moved lasso line keeps its id set ⇒
 * same key ⇒ cached text is reused, only bounds refresh); lines that no longer exist are evicted.
 *
 * The grouping, bounds and [isRecognizableInk] steps are seams (defaulting to the real ink
 * functions) so the cache logic is JVM-testable without ink natives.
 */
suspend fun buildRecognizedLinesCached(
    pageId: String,
    strokes: List<CanvasStroke>,
    recognizer: HandwritingRecognizer,
    cache: RecognitionCacheRepository,
    recognizableInk: (Stroke) -> Boolean = ::isRecognizableInk,
    groupLines: (List<CanvasStroke>) -> List<List<CanvasStroke>> = ::groupCanvasStrokesIntoLines,
    boundsOf: (List<Stroke>) -> FloatArray = ::lineBounds,
    now: () -> Long = System::currentTimeMillis,
): List<RecognizedLine> {
    val cached = cache.getForPage(pageId)
    val cachedKeys = cached.map { it.id }.toSet()

    val recognizable = strokes.filter { recognizableInk(it.stroke) }
    if (recognizable.isEmpty()) {
        cache.deleteByIds(cachedKeys.toList())
        return emptyList()
    }

    val cachedTextByKey = cached.associate { it.id to it.text }
    val timestamp = now()
    val currentKeys = mutableSetOf<String>()
    val rows = mutableListOf<RecognizedLineEntity>()
    val result = mutableListOf<RecognizedLine>()

    groupLines(recognizable).forEach { line ->
        val strokeIds = line.map { it.id }
        val key = recognizedLineKey(pageId, strokeIds)
        currentKeys += key
        val bounds = boundsOf(line.map { it.stroke })
        // Cache hit → reuse text; miss → recognize (usually just the line being written).
        val text = cachedTextByKey[key]
            ?: recognizer.recognize(line.map { it.stroke }).getOrNull()?.trim().orEmpty()
        if (text.isEmpty()) return@forEach
        rows += RecognizedLineEntity(
            id = key,
            pageId = pageId,
            strokeIds = strokeIds.joinToString(","),
            text = text,
            minX = bounds[0], minY = bounds[1], maxX = bounds[2], maxY = bounds[3],
            recognizedAt = timestamp,
        )
        result += RecognizedLine(text = text, minX = bounds[0], minY = bounds[1], maxX = bounds[2], maxY = bounds[3])
    }

    cache.upsertAll(rows)
    cache.deleteByIds((cachedKeys - currentKeys).toList())
    return result
}
