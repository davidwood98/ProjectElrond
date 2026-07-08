package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure Quick Nav search filtering (FA-24). */
class QuickNavSearchTest {

    private fun subject(id: String, name: String, parentId: String? = null) =
        Subject(id = id, parentId = parentId, name = name, colorId = 0, sortOrder = 0L, createdAt = 0L, modifiedAt = 0L)

    private fun notebook(id: String, title: String) = NotebookSummary(
        notebookId = id,
        title = title,
        coverPageId = "$id-p1",
        pageCount = 1,
        modifiedAt = 0L,
        lastViewedPageId = "$id-p1",
        lastOpenedAt = 0L,
    )

    private val tree = SubjectTree.build(
        listOf(subject("s1", "Maths"), subject("s2", "History")),
    )
    private val notebooks = mapOf<String?, List<NotebookSummary>>(
        "s1" to listOf(notebook("nb1", "Algebra"), notebook("nb2", "Calculus")),
        "s2" to listOf(notebook("nb3", "Rome")),
        null to listOf(notebook("nb4", "Scratch pad")),
    )

    @Test
    fun `blank query returns every notebook`() {
        val all = QuickNavSearch.filterNotebooks("  ", tree, notebooks)
        assertEquals(setOf("nb1", "nb2", "nb3", "nb4"), all.map { it.notebookId }.toSet())
    }

    @Test
    fun `matches notebook titles case-insensitively`() {
        val hits = QuickNavSearch.filterNotebooks("aLgEb", tree, notebooks)
        assertEquals(listOf("nb1"), hits.map { it.notebookId })
    }

    @Test
    fun `matching a subject name pulls in all its notebooks`() {
        val hits = QuickNavSearch.filterNotebooks("maths", tree, notebooks)
        assertEquals(setOf("nb1", "nb2"), hits.map { it.notebookId }.toSet())
    }

    @Test
    fun `a notebook matching by both title and subject appears once`() {
        // "a" matches the Maths subject? No — matches titles Algebra/Calculus/Scratch pad AND
        // subject Maths; nb1/nb2 qualify twice (title + subject) but must appear once.
        val hits = QuickNavSearch.filterNotebooks("a", tree, notebooks)
        assertEquals(hits.size, hits.map { it.notebookId }.toSet().size)
        assertTrue(hits.map { it.notebookId }.containsAll(listOf("nb1", "nb2")))
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(QuickNavSearch.filterNotebooks("zzz", tree, notebooks).isEmpty())
    }
}
