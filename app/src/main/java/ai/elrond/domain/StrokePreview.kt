package ai.elrond.domain

/**
 * One stroke of a note-card thumbnail (FA-23): its normalized (0..1) polyline plus the style the
 * card needs to draw it faithfully — per-stroke colour and whether it's a wide translucent
 * highlighter mark. Pure data, decoded straight from stored rows (no ink natives).
 */
data class StrokePreview(
    val points: List<Pair<Float, Float>>,
    val colorArgb: Int,
    val isHighlighter: Boolean,
)
