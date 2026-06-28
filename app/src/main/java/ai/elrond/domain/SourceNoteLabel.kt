package ai.elrond.domain

/**
 * Resolves the live notebook-title label for a to-do or calendar item that links back to a source
 * page (FA-20). A notebook's title is its cover (first) page's title — pages > 1 have no title of
 * their own — so the label reads cleanly as just the notebook title regardless of which page the
 * item came from. The link still opens the *exact* source page (the caller keeps the sourcePageId);
 * this only controls the displayed text. Resolved from the current page list so a renamed notebook
 * updates live; null when the source page no longer exists (the UI falls back to the stored snapshot).
 */
object SourceNoteLabel {
    /** Live notebook title for [pageId] given all [pages], or null if the page no longer exists. */
    fun resolve(pageId: String, pages: List<NotePage>): String? {
        val page = pages.firstOrNull { it.id == pageId } ?: return null
        val cover = pages.filter { it.notebookId == page.notebookId }.minByOrNull { it.pageNumber } ?: page
        return cover.displayTitle()
    }
}
