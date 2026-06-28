package ai.elrond.domain

/**
 * Resolves the live notebook-title label for a to-do or calendar item that links back to a source
 * page (FA-20). The notebook title is a notebook-level property ([notebookTitle]: its name, else the
 * cover page's timestamp) — independent of page order — so the label reads cleanly regardless of which
 * page the item came from. The link still opens the *exact* source page (the caller keeps the
 * sourcePageId); this only controls the displayed text. Resolved from the current page list +
 * notebook names so a renamed notebook updates live; null when the source page no longer exists (the
 * UI falls back to the stored snapshot).
 */
object SourceNoteLabel {
    /**
     * Live notebook title for [pageId] given all [pages] and the notebookId→name map
     * [notebookNames], or null if the page no longer exists. With no names the cover timestamp shows.
     */
    fun resolve(
        pageId: String,
        pages: List<NotePage>,
        notebookNames: Map<String, String> = emptyMap(),
    ): String? {
        val page = pages.firstOrNull { it.id == pageId } ?: return null
        val cover = pages.filter { it.notebookId == page.notebookId }.minByOrNull { it.pageNumber } ?: page
        return notebookTitle(notebookNames[page.notebookId].orEmpty(), cover)
    }
}
