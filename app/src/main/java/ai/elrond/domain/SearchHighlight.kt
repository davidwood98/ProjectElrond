package ai.elrond.domain

/**
 * One on-canvas search highlight box (FA-24c search-result mode): the page-space bounds of a matching
 * handwriting line. Positioned on screen the same way ink/link boxes are — `PageTransform.pageToScreen*`
 * plus the live page transform.
 */
data class SearchHighlight(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)
