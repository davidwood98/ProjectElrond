package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FA-20: live "Notebook → Page N" resolution for to-do/calendar source links. */
class SourceNoteLabelTest {

    private fun page(id: String, notebookId: String, title: String, pageNumber: Int) =
        NotePage(
            id = id,
            notebookId = notebookId,
            customTitle = title,
            createdAt = 0L,
            modifiedAt = 0L,
            pageNumber = pageNumber,
        )

    @Test
    fun `single-page notebook shows the title`() {
        val pages = listOf(page("p1", "nb1", "Shopping", 1))
        assertEquals("Shopping", SourceNoteLabel.resolve("p1", pages))
    }

    @Test
    fun `multi-page notebook shows the cover title for any page (no page number)`() {
        val pages = listOf(
            page("p1", "nb1", "Maths", 1),
            page("p2", "nb1", "ignored-title", 2),
            page("p3", "nb1", "ignored", 3),
        )
        // The label is always the cover (page 1) title — pages > 1 have no title of their own.
        assertEquals("Maths", SourceNoteLabel.resolve("p2", pages))
        assertEquals("Maths", SourceNoteLabel.resolve("p3", pages))
    }

    @Test
    fun `resolves the cover title using only the page's own notebook`() {
        val pages = listOf(
            page("p1", "nb1", "Solo", 1),
            page("o1", "nb2", "Other", 1),
            page("o2", "nb2", "Other 2", 2),
        )
        assertEquals("Solo", SourceNoteLabel.resolve("p1", pages))
        assertEquals("Other", SourceNoteLabel.resolve("o2", pages))
    }

    @Test
    fun `deleted source resolves to null`() {
        val pages = listOf(page("p1", "nb1", "Here", 1))
        assertNull(SourceNoteLabel.resolve("gone", pages))
    }

    @Test
    fun `notebook name wins and is independent of page order`() {
        val names = mapOf("nb1" to "Renamed")
        val pages = listOf(page("p1", "nb1", "OldCover", 1), page("p2", "nb1", "x", 2))
        assertEquals("Renamed", SourceNoteLabel.resolve("p1", pages, names))
        assertEquals("Renamed", SourceNoteLabel.resolve("p2", pages, names))
        // Reorder so p2 is now the cover (page 1) — the notebook name still shows (FA-20 fix).
        val reordered = listOf(page("p2", "nb1", "x", 1), page("p1", "nb1", "OldCover", 2))
        assertEquals("Renamed", SourceNoteLabel.resolve("p1", reordered, names))
    }
}
