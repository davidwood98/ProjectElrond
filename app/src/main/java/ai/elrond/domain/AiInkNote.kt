package ai.elrond.domain

/**
 * An AI response rendered on the canvas as handwriting-style text, visually distinct from user ink.
 *
 * The box is **content-hugging** (FA-21): it sizes to its text up to a full-line cap ([widthPx]), so
 * a short answer is a tight box and a long one wraps to the cap. It is selectable (1.5s press-and-hold
 * or by lasso) and then moves/resizes via the shared selection chrome:
 *  - a corner drag scales ratio-locked and grows/shrinks the **font** ([fontScale]) to keep the text
 *    fitting the box;
 *  - a left/right edge drag changes [widthPx] at a constant font size, so the text reflows (narrower
 *    box = more lines = taller).
 *
 * @param x,y top-left position in page-space pixels.
 * @param widthPx the box's width cap in page-space pixels (defaults to ~a full line on create); the
 *                rendered box hugs its text up to this width.
 * @param heightPx box height in page-space pixels; null means wrap to content (the usual case — the
 *                height follows the text as the font or width changes).
 * @param fontScale multiplier on the base font size, baked in by a ratio-locked corner resize (and by
 *                a group scale that includes this box). 1 = the base size.
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
    val fontScale: Float = 1f,
    val isError: Boolean = false,
    val sourceQuestion: String? = null,
    val suggestedQuestion: String? = null,
) {
    companion object {
        const val MIN_WIDTH_PX = 160f
        const val MIN_HEIGHT_PX = 56f
        /** Fallback width when the canvas size isn't known yet (e.g. unit tests). */
        const val FALLBACK_WIDTH_PX = 800f
        /** Font-scale clamp for ratio-locked corner resizes (and group scales that include a box). */
        const val MIN_FONT_SCALE = 0.3f
        const val MAX_FONT_SCALE = 6f
    }
}

/** Canvas-pixel position for placing a new AI note. */
data class NotePosition(val x: Float, val y: Float)
