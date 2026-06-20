package ai.elrond.domain

/**
 * An AI response rendered on the canvas as handwriting-style text —
 * moveable and freely resizable (width and height are independent, so the
 * aspect ratio is not locked), and visually distinct from user ink.
 *
 * @param x,y top-left position in canvas pixels.
 * @param widthPx box width in canvas pixels (defaults to ~a full line on create).
 * @param heightPx box height in canvas pixels; null means wrap to content until
 *                 the user drags the resize handle vertically.
 * @param isError true for an error-type response (e.g. "request unclear") — rendered with
 *                Edit-prompt / Okay controls and NOT persisted (transient, in-memory only).
 * @param sourceQuestion the recognized question text, kept on an error note so "Edit prompt"
 *                can pre-fill the editable box for a re-send. Null for normal answers.
 * @param suggestedQuestion on an unclear-error note, the AI's single best guess at what the
 *                user meant ("Did you mean …?"). When present the note offers a Yes/No
 *                clarification: Yes re-sends this guess, No falls back to Edit-prompt/Okay.
 *                Null when the model could not even guess. In-memory only (never persisted).
 */
data class AiInkNote(
    val id: String,
    val text: String,
    val x: Float,
    val y: Float,
    val widthPx: Float,
    val heightPx: Float? = null,
    val isError: Boolean = false,
    val sourceQuestion: String? = null,
    val suggestedQuestion: String? = null,
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
