package ai.elrond.domain

/**
 * An on-canvas link box referencing another notebook (FA-24 Phase 1). Selectable, movable and
 * scalable through the unified [SelectionState] alongside strokes and [AiInkNote]s; a plain tap
 * opens the target notebook.
 *
 * [targetNotebookId] null means the target was deleted (the entity's FK SET_NULL) — a broken
 * link, rendered as "Reference not found" and non-interactive on tap. [targetPageId] is
 * reserved for future page-level links and unused. [linkText] is the target's title cached at
 * link time. [createdAt] round-trips verbatim through persistence (never re-stamped on save):
 * it drives the Backlinks ordering, so it must survive unrelated autosaves.
 */
data class NotebookLink(
    val id: String,
    val targetNotebookId: String?,
    val targetPageId: String? = null,
    val x: Float,
    val y: Float,
    val widthPx: Float,
    /** Null = wrap content height, same convention as [AiInkNote]. */
    val heightPx: Float? = null,
    val linkText: String,
    val createdAt: Long,
) {
    val isBroken: Boolean get() = targetNotebookId == null

    companion object {
        const val MIN_WIDTH_PX = 160f
        const val MIN_HEIGHT_PX = 56f
        const val DEFAULT_WIDTH_PX = 280f
    }
}

/** Default placement for a newly created link box: centred in the current viewport (page space). */
fun defaultLinkPosition(
    transform: PageTransform,
    canvasWidthPx: Float,
    canvasHeightPx: Float,
    linkWidthPx: Float = NotebookLink.DEFAULT_WIDTH_PX,
): NotePosition {
    val centreX = if (canvasWidthPx > 0f) transform.screenToPageX(canvasWidthPx / 2f) else 0f
    val centreY = if (canvasHeightPx > 0f) transform.screenToPageY(canvasHeightPx / 2f) else 0f
    return NotePosition(
        x = (centreX - linkWidthPx / 2f).coerceAtLeast(0f),
        y = centreY.coerceAtLeast(0f),
    )
}

/** One row in a notebook's "referenced by" (Backlinks) list. */
data class Backlink(
    val linkId: String,
    val sourcePageId: String,
    val sourceTitle: String,
)
