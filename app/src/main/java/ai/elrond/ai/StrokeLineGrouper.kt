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
}
