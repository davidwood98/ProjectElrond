package ai.elrond.ai

/**
 * An AI response rendered on the canvas as handwriting-style text —
 * moveable, resizable, and visually distinct from user ink.
 *
 * @param x,y top-left position in canvas pixels.
 * @param scale multiplies the base font size and note width (pinch/handle resize).
 */
data class AiInkNote(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
) {
    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 3f
    }
}

/** Canvas-pixel position for placing a new AI note. */
data class NotePosition(val x: Float, val y: Float)
