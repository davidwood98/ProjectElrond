package ai.elrond.data

import ai.elrond.domain.TagColor
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Real Room SQL (Robolectric) for notebook tags (FA-24): get-or-create by unique name with the
 * deterministic stored colour, many-to-many assignment, idempotent assign, both cascade
 * directions, and the grouped observe shape both UIs consume.
 */
@RunWith(RobolectricTestRunner::class)
class TagRepositoryTest {

    private lateinit var db: ElrondDatabase
    private lateinit var repo: TagRepository
    private val ids = AtomicInteger(0)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TagRepository(
            tagDao = db.tagDao(),
            notebookTagDao = db.notebookTagDao(),
            newId = { "t${ids.incrementAndGet()}" },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedNotebook(id: String) {
        db.notebookDao().insert(NotebookEntity(id = id, name = "N-$id", createdAt = 0))
    }

    @Test
    fun `createTag stores the deterministic name colour and trims the name`() = runTest {
        val tag = repo.createTag("  physics ")
        assertEquals("physics", tag.name)
        assertEquals(TagColor.forName("physics"), tag.colorArgb)
    }

    @Test
    fun `createTag with an existing name returns the existing tag, not a duplicate`() = runTest {
        val first = repo.createTag("maths")
        val second = repo.createTag("maths")
        assertEquals(first.id, second.id)
        assertEquals(1, repo.observeTags().first().size)
    }

    @Test
    fun `assignment is many-to-many on both sides`() = runTest {
        seedNotebook("nb1"); seedNotebook("nb2")
        val shared = repo.createTag("shared")
        val only1 = repo.createTag("only-nb1")
        repo.assignTag("nb1", shared.id)
        repo.assignTag("nb2", shared.id)
        repo.assignTag("nb1", only1.id)

        val byNotebook = repo.observeNotebookTags().first()
        assertEquals(setOf("only-nb1", "shared"), byNotebook["nb1"]!!.map { it.name }.toSet())
        assertEquals(listOf("shared"), byNotebook["nb2"]!!.map { it.name })
    }

    @Test
    fun `assignTag is idempotent`() = runTest {
        seedNotebook("nb1")
        val tag = repo.createTag("x")
        repo.assignTag("nb1", tag.id)
        repo.assignTag("nb1", tag.id) // no throw, no duplicate row
        assertEquals(1, repo.observeNotebookTags().first()["nb1"]!!.size)
    }

    @Test
    fun `removeTag detaches one notebook without touching the others`() = runTest {
        seedNotebook("nb1"); seedNotebook("nb2")
        val tag = repo.createTag("shared")
        repo.assignTag("nb1", tag.id)
        repo.assignTag("nb2", tag.id)

        repo.removeTag("nb1", tag.id)

        val byNotebook = repo.observeNotebookTags().first()
        assertTrue(byNotebook["nb1"] == null)
        assertEquals(1, byNotebook["nb2"]!!.size)
        assertEquals(1, repo.observeTags().first().size) // the tag itself survives
    }

    @Test
    fun `deleting a notebook cascades its memberships, the tag survives`() = runTest {
        seedNotebook("nb1")
        val tag = repo.createTag("x")
        repo.assignTag("nb1", tag.id)

        db.notebookDao().deleteById("nb1")

        assertTrue(repo.observeNotebookTags().first().isEmpty())
        assertEquals(1, repo.observeTags().first().size)
    }

    @Test
    fun `deleting a tag cascades its memberships across every notebook`() = runTest {
        seedNotebook("nb1"); seedNotebook("nb2")
        val doomed = repo.createTag("doomed")
        val keeper = repo.createTag("keeper")
        repo.assignTag("nb1", doomed.id)
        repo.assignTag("nb2", doomed.id)
        repo.assignTag("nb1", keeper.id)

        db.tagDao().deleteById(doomed.id)

        val byNotebook = repo.observeNotebookTags().first()
        assertEquals(listOf("keeper"), byNotebook["nb1"]!!.map { it.name })
        assertTrue(byNotebook["nb2"] == null)
    }
}
