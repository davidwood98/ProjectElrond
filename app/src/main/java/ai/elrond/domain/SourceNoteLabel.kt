package ai.elrond.domain

/**
 * Resolves the live "Notebook → Page N" label for a to-do or calendar item that links back to a
 * source page (FA-20). The page may since have moved within its notebook (page numbers are mutable),
 * so the label is derived from the *current* page list rather than a stored snapshot — the snapshot
 * title is only the fallback for a deleted source.
 *
 * The notebook's display name is its cover (first) page's title, matching the browser/tabs (a
 * per-note notebook stores no separate name). The "→ Page N" suffix is shown only when the notebook
 * actually has more than one page, so single-page notebooks (the common case) read as just the title
 * without redundant "→ Page 1" noise.
 */
object SourceNoteLabel {
    /** Live label for [pageId] given all [pages], or null if the page no longer exists. */
    fun resolve(pageId: String, pages: List<NotePage>): String? {
        val page = pages.firstOrNull { it.id == pageId } ?: return null
        val notebookPages = pages.filter { it.notebookId == page.notebookId }
        val cover = notebookPages.minByOrNull { it.pageNumber } ?: page
        val name = cover.displayTitle()
        return if (notebookPages.size > 1) "$name → Page ${page.pageNumber}" else name
    }
}
