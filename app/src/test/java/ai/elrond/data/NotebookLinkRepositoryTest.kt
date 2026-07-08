package ai.elrond.data

import ai.elrond.domain.NotebookLink
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real Room SQL (Robolectric) for notebook links (FA-24): the replaceForPage round-trip, the
 * FK behaviours (target delete → SET NULL broken state; source-page delete → cascade), the
 * backlinks JOIN, the last-viewed-page resolution used by tap-to-open, and the createdAt
 * round-trip that Backlinks ordering depends on.
 */
@RunWith(RobolectricTestRunner::class)
class NotebookLinkRepositoryTest {

    private lateinit var db: ElrondDatabase
    private lateinit var repo: NotebookLinkRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = NotebookLinkRepository(linkDao = db.notebookLinkDao(), clock = { 0L })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedNotebook(id: String) {
        db.notebookDao().insert(NotebookEntity(id = id, name = "N-$id", createdAt = 0))
    }

    private suspend fun seedPage(id: String, notebookId: String, lastOpenedAt: Long = 0, pageNumber: Int = 1) {
        db.notePageDao().insert(
            NotePageEntity(
                id = id,
                notebookId = notebookId,
                customTitle = null,
                createdAt = 0,
                modifiedAt = 0,
                lastOpenedAt = lastOpenedAt,
                pageNumber = pageNumber,
            ),
        )
    }

    private fun link(id: String, target: String?, createdAt: Long = 0L) = NotebookLink(
        id = id,
        targetNotebookId = target,
        x = 10f,
        y = 20f,
        widthPx = 280f,
        heightPx = 56f,
        linkText = "Target title",
        createdAt = createdAt,
    )

    @Test
    fun `replaceForPage round-trips links in createdAt order`() = runTest {
        seedNotebook("nb-src"); seedPage("p1", "nb-src")
        seedNotebook("nb-a"); seedNotebook("nb-b")

        repo.replaceForPage("p1", listOf(link("l2", "nb-b", createdAt = 5L), link("l1", "nb-a", createdAt = 1L)))
        val loaded = repo.loadForPage("p1")

        assertEquals(listOf("l1", "l2"), loaded.map { it.id }) // ordered by createdAt
        val first = loaded.first()
        assertEquals("nb-a", first.targetNotebookId)
        assertEquals(10f, first.x, 0f)
        assertEquals(20f, first.y, 0f)
        assertEquals(280f, first.widthPx, 0f)
        assertEquals("Target title", first.linkText)
        assertEquals(1L, first.createdAt) // never re-stamped on save (Backlinks ordering)
    }

    @Test
    fun `deleting the target notebook breaks the link instead of deleting or crashing`() = runTest {
        seedNotebook("nb-src"); seedPage("p1", "nb-src")
        seedNotebook("nb-target")
        repo.replaceForPage("p1", listOf(link("l1", "nb-target")))

        db.notebookDao().deleteById("nb-target")

        val reloaded = repo.loadForPage("p1").single()
        assertNull(reloaded.targetNotebookId) // FK SET NULL
        assertTrue(reloaded.isBroken)
        assertEquals("Target title", reloaded.linkText) // cached label survives for the broken state
    }

    @Test
    fun `deleting the source page cascades its links away (and out of backlinks)`() = runTest {
        seedNotebook("nb-src"); seedPage("p1", "nb-src")
        seedNotebook("nb-target")
        repo.replaceForPage("p1", listOf(link("l1", "nb-target")))
        assertEquals(1, repo.observeBacklinks("nb-target").first().size)

        db.notePageDao().deleteById("p1")

        assertTrue(repo.loadForPage("p1").isEmpty())
        assertTrue(repo.observeBacklinks("nb-target").first().isEmpty())
    }

    @Test
    fun `observeBacklinks returns only links targeting the notebook, newest first`() = runTest {
        seedNotebook("nb-a"); seedPage("pa", "nb-a")
        seedNotebook("nb-b"); seedPage("pb", "nb-b")
        seedNotebook("nb-target")
        repo.replaceForPage("pa", listOf(link("l1", "nb-target", createdAt = 1L)))
        repo.replaceForPage("pb", listOf(link("l2", "nb-target", createdAt = 2L), link("l3", "nb-a", createdAt = 3L)))

        val backlinks = repo.observeBacklinks("nb-target").first()

        assertEquals(listOf("l2", "l1"), backlinks.map { it.id }) // newest first, l3 excluded
        assertEquals("nb-b", backlinks[0].sourceNotebookId) // resolved through the page JOIN
        assertEquals("pa", backlinks[1].sourcePageId)
    }

    @Test
    fun `createdAt survives a replaceForPage that touches unrelated links on the page`() = runTest {
        seedNotebook("nb-src"); seedPage("p1", "nb-src")
        seedNotebook("nb-a"); seedNotebook("nb-b")
        repo.replaceForPage("p1", listOf(link("l1", "nb-a", createdAt = 42L)))

        // A later autosave rewrites the page's links wholesale (l1 unchanged, l2 added).
        val l1 = repo.loadForPage("p1").single()
        repo.replaceForPage("p1", listOf(l1, link("l2", "nb-b", createdAt = 100L)))

        assertEquals(42L, repo.loadForPage("p1").first { it.id == "l1" }.createdAt)
    }

    @Test
    fun `lastViewedPageId falls back to a live page and is null for an empty notebook`() = runTest {
        seedNotebook("nb-1")
        seedPage("p-cover", "nb-1", lastOpenedAt = 0, pageNumber = 1)
        seedPage("p-recent", "nb-1", lastOpenedAt = 50L, pageNumber = 2)

        // Most recently viewed wins.
        assertEquals("p-recent", db.notePageDao().mostRecentlyViewedPageId("nb-1"))

        // Deleting it falls back to the next live page (the cover).
        db.notePageDao().deleteById("p-recent")
        assertEquals("p-cover", db.notePageDao().mostRecentlyViewedPageId("nb-1"))

        // No pages at all → null (the ViewModel shows a transient message instead of opening).
        db.notePageDao().deleteById("p-cover")
        assertNull(db.notePageDao().mostRecentlyViewedPageId("nb-1"))
    }
}
