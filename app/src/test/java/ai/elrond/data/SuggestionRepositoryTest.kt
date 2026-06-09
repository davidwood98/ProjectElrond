package ai.elrond.data

import ai.elrond.extract.PendingSuggestion
import ai.elrond.extract.SuggestionType
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
    fun `dismiss hides from the popup list but keeps the content for de-dup`() = runTest {
        repo.add(listOf(todo("Buy milk")))
        val id = repo.observePending("p1").first().single().id

        repo.dismiss(id)

        assertTrue(repo.observePending("p1").first().isEmpty())
        assertTrue("buy milk" in repo.existingContents("p1"))
    }

    @Test
    fun `remove deletes the suggestion entirely`() = runTest {
        repo.add(listOf(todo("Buy milk")))
        val id = repo.observePending("p1").first().single().id

        repo.remove(id)

        assertTrue(repo.observePending("p1").first().isEmpty())
        assertFalse("buy milk" in repo.existingContents("p1"))
    }
}
