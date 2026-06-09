package ai.elrond.ai

/**
 * Groups handwriting strokes into horizontal lines by vertical-span overlap.
 *
 * Pure index-based logic (JVM-testable); [groupStrokesIntoLines] adapts it to
 * real ink strokes. Limitation: long descenders that physically overlap the
 * line below can merge two lines — acceptable for the POC.
 */
object StrokeLineGrouper {

    data class Span(val minY: Float, val maxY: Float)

    /**
     * @return groups of input indices, ordered top-to-bottom; indices within a
     *         group keep ascending (temporal) order.
     */
    fun groupIntoLines(spans: List<Span>): List<List<Int>> {
        if (spans.isEmpty()) return emptyList()

        val byTop = spans.indices.sortedBy { spans[it].minY }
        val lines = mutableListOf<MutableList<Int>>()
        var currentBottom = Float.NEGATIVE_INFINITY

        for (index in byTop) {
            val span = spans[index]
            if (lines.isEmpty() || span.minY > currentBottom) {
                lines += mutableListOf(index)
                currentBottom = span.maxY
            } else {
                lines.last() += index
                currentBottom = maxOf(currentBottom, span.maxY)
            }
        }
        return lines.map { it.sorted() }
    }

    /** Default multiplier: a vertical gap beyond this × a line's height is a paragraph break. */
    const val DEFAULT_PARAGRAPH_GAP_FACTOR = 1.2f
    private const val MIN_LINE_HEIGHT = 1f

    /**
     * Indices of the contiguous block of lines directly above [triggerIndex] — walking up
     * until the first "paragraph" gap (a vertical gap larger than [gapFactor] × the line's
     * own height). Used to treat a multi-line handwritten question as a single prompt;
     * lines above the gap stay as page context.
     *
     * @return indices top-to-bottom; empty when [triggerIndex] is the first line or out of range.
     */
    fun blockAbove(
        lineSpans: List<Span>,
        triggerIndex: Int,
        gapFactor: Float = DEFAULT_PARAGRAPH_GAP_FACTOR,
    ): List<Int> {
        if (triggerIndex !in 1..lineSpans.lastIndex) return emptyList()
        val block = ArrayDeque<Int>()
        var lower = triggerIndex
        for (i in triggerIndex - 1 downTo 0) {
            val gap = lineSpans[lower].minY - lineSpans[i].maxY
            val lineHeight = (lineSpans[i].maxY - lineSpans[i].minY).coerceAtLeast(MIN_LINE_HEIGHT)
            if (gap > gapFactor * lineHeight) break
            block.addFirst(i)
            lower = i
        }
        return block.toList()
    }
}
