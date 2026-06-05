package ai.elrond.ai

/**
 * An AI response rendered on the canvas as handwriting-style text —
 * moveable and freely resizable (width and height are independent, so the
 * aspect ratio is not locked), and visually distinct from user ink.
 *
 * @param x,y top-left position in canvas pixels.
 * @param widthPx box width in canvas pixels (defaults to ~a full line on create).
 * @param heightPx box height in canvas pixels; null means wrap to content until
 *                 the user drags the resize handle vertically.
 */
data class AiInkNote(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val widthPx: Float,
    val heightPx: Float? = null,
) {
    companion object {
        const val MIN_WIDTH_PX = 160f
        const val MIN_HEIGHT_PX = 56f
        /** Fallback width when the canvas size isn't known yet (e.g. unit tests). */
        const val FALLBACK_WIDTH_PX = 800f
    }
}

/** Canvas-pixel position for placing a new AI note. */
data class NotePosition(val x: Float, val y: Float)
