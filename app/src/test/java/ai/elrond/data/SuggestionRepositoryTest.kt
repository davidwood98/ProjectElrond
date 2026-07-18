package ai.elrond.data

import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.SuggestionType
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Room behaviour for the FA-2 worker→UI suggestion channel (Robolectric). */
@RunWith(RobolectricTestRunner::class)
class SuggestionRepositoryTest {

    private lateinit var db: ElrondDatabase
    private lateinit var repo: SuggestionRepository

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java).allowMainThreadQueries().build()
        db.notebookDao().insert(NotebookEntity(id = "nb1", name = "N", createdAt = 0))
        db.notePageDao().insert(
            NotePageEntity(id = "p1", notebookId = "nb1", customTitle = null, createdAt = 0, modifiedAt = 0),
        )
        repo = SuggestionRepository(db.pendingSuggestionDao())
    }

    @After
    fun tearDown() = db.close()

    private fun todo(content: String) =
        PendingSuggestion(pageId = "p1", type = SuggestionType.TODO, content = content, x = 1f, y = 2f)

    @Test
    fun `added suggestions are observed for their page`() = runTest {
        repo.add(listOf(todo("Buy milk"), todo("Call bank")))
        assertEquals(
            setOf("Buy milk", "Call bank"),
            repo.observePending("p1").first().map { it.content }.toSet(),
        )
    }

    @Test
    fun `dismiss (ignore) hides from the popup list but is NOT counted as rejected`() = runTest {
        repo.add(listOf(todo("Buy milk")))
        val id = repo.observePending("p1").first().single().id

        repo.dismiss(id) // ignore / not-now

        assertTrue(repo.observePending("p1").first().isEmpty()) // no popup
        assertTrue("buy milk" in repo.existingContents("p1")) // still known to the background de-dup
        assertFalse("buy milk" in repo.rejectedContents("p1")) // ignored != rejected → /Q re-offers
    }

    @Test
    fun `reject hides the popup AND is counted as rejected so a written Q stays silent`() = runTest {
        repo.add(listOf(todo("Buy milk")))
        val id = repo.observePending("p1").first().single().id

        repo.reject(id)

        assertTrue(repo.observePending("p1").first().isEmpty()) // no popup
        assertTrue("buy milk" in repo.rejectedContents("p1")) // /Q de-dups against this
    }

    @Test
    fun `remove deletes the suggestion entirely`() = runTest {
        repo.add(listOf(todo("Buy milk")))
        val id = repo.observePending("p1").first().single().id

        repo.remove(id)

        assertTrue(repo.observePending("p1").first().isEmpty())
        assertFalse("buy milk" in repo.existingContents("p1"))
    }

    @Test
    fun `rejectedContents reports only rejected suggestions, not ignored or pending ones`() = runTest {
        repo.add(listOf(todo("Buy milk"), todo("Call bank"), todo("Email Sam")))
        val pending = repo.observePending("p1").first()
        repo.dismiss(pending.single { it.content == "Call bank" }.id) // ignored (not-now)
        repo.reject(pending.single { it.content == "Email Sam" }.id) // rejected

        // Only the rejected item is reported; pending "Buy milk" and ignored "Call bank" are not.
        assertEquals(setOf("email sam"), repo.rejectedContents("p1"))
    }

    @Test
    fun `claimPendingTodos deletes the matching pending popup so it cannot double-add or poison dedup`() = runTest {
        repo.add(listOf(todo("Buy milk"), todo("Call bank")))

        repo.claimPendingTodos("p1", listOf("buy milk"))

        // The claimed item leaves the popup queue (so /Q's sheet is the only way to add it)...
        assertEquals(setOf("Call bank"), repo.observePending("p1").first().map { it.content }.toSet())
        // ...and is fully removed — NOT counted as rejected, so a later /Q re-offers it.
        assertFalse("buy milk" in repo.existingContents("p1"))
        assertFalse("buy milk" in repo.rejectedContents("p1"))
    }

    @Test
    fun `recordHandled de-dups future runs without ever showing in the popup`() = runTest {
        // The manual /Q path claims the items it proposed so the background runner can't re-add.
        repo.recordHandled(listOf(todo("Buy milk")))

        assertTrue("never surfaces as a pending popup", repo.observePending("p1").first().isEmpty())
        assertTrue("but de-dups by content", "buy milk" in repo.existingContents("p1"))
        assertTrue(
            "and de-dups type-namespaced",
            "TODO:buy milk" in repo.existingTypedContents("p1"),
        )
    }

    // ── FA-24d Level 2: notebook-scoped TAG suggestions ────────────────────────────────────────

    // pageId "p1" is a real page (see setUp) — it only satisfies the page FK; TAG queries key off notebookId.
    private fun tag(name: String) = PendingSuggestion(
        pageId = "p1", type = SuggestionType.TAG, content = name, x = 0f, y = 0f, notebookId = "nb1",
    )

    @Test
    fun `TAG suggestions are observed by notebook, not page`() = runTest {
        repo.add(listOf(tag("physics"), tag("revision")))
        assertEquals(
            setOf("physics", "revision"),
            repo.observeTagSuggestions("nb1").first().map { it.content }.toSet(),
        )
    }

    @Test
    fun `existingTagContents spans handled rows for de-dup`() = runTest {
        repo.add(listOf(tag("physics")))
        val id = repo.observeTagSuggestions("nb1").first().single().id
        repo.markHandled(id) // accepted → leaves the active queue but still blocks re-suggestion

        assertTrue(repo.observeTagSuggestions("nb1").first().isEmpty())
        assertTrue("physics" in repo.existingTagContents("nb1"))
    }

    @Test
    fun `clearActiveTagSuggestions drops un-actioned rows but keeps handled ones`() = runTest {
        repo.add(listOf(tag("physics"), tag("revision")))
        val physicsId = repo.observeTagSuggestions("nb1").first().first { it.content == "physics" }.id
        repo.markHandled(physicsId)

        repo.clearActiveTagSuggestions("nb1")

        // The un-actioned "revision" is gone; the handled "physics" still de-dups future runs.
        assertTrue(repo.observeTagSuggestions("nb1").first().isEmpty())
        assertTrue("physics" in repo.existingTagContents("nb1"))
        assertFalse("revision" in repo.existingTagContents("nb1"))
    }
}
