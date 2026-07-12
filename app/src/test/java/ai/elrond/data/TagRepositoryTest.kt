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
    fun `removing the last membership erases the orphaned tag from the menu`() = runTest {
        seedNotebook("nb1")
        val tag = repo.createTag("lonely")
        repo.assignTag("nb1", tag.id)

        repo.removeTag("nb1", tag.id)

        assertTrue(repo.observeTags().first().isEmpty()) // gone, not lingering unassigned
    }

    @Test
    fun `deleting a notebook cascades its memberships and pruneOrphans erases the tag`() = runTest {
        seedNotebook("nb1")
        val tag = repo.createTag("x")
        repo.assignTag("nb1", tag.id)

        // The FK cascade happens inside SQLite (no repository code runs) — memberships vanish...
        db.notebookDao().deleteById("nb1")
        assertTrue(repo.observeNotebookTags().first().isEmpty())
        assertEquals(1, repo.observeTags().first().size)

        // ...and the tagging-surface sweep erases the now-orphaned tag.
        repo.pruneOrphans()
        assertTrue(repo.observeTags().first().isEmpty())
    }

    @Test
    fun `pruneOrphans leaves tags that still have a membership`() = runTest {
        seedNotebook("nb1")
        val kept = repo.createTag("kept")
        repo.assignTag("nb1", kept.id)
        repo.createTag("orphan") // created but never assigned

        repo.pruneOrphans()

        assertEquals(listOf("kept"), repo.observeTags().first().map { it.name })
    }

    @Test
    fun `notebook tags come back newest assignment first`() = runTest {
        // The header row renders list order left→right, so newest-first makes a new tag
        // generate at the left while older pills keep their right-anchored spots.
        seedNotebook("nb1")
        val first = repo.createTag("first")
        val second = repo.createTag("second")
        repo.assignTag("nb1", first.id)
        repo.assignTag("nb1", second.id)

        assertEquals(
            listOf("second", "first"),
            repo.observeNotebookTags().first()["nb1"]!!.map { it.name },
        )
    }

    @Test
    fun `repairUnreadableColors re-resolves dark-shade tags and leaves readable ones alone`() = runTest {
        seedNotebook("nb1")
        // A legacy tag stored with a hue's darkest shade (pre-exclusion), planted directly.
        val darkest = ai.elrond.domain.SubjectPalette.argb(ai.elrond.domain.SubjectPalette.SHADE_COUNT - 1)
        db.tagDao().insert(TagEntity(id = "legacy", name = "legacy", colorArgb = darkest))
        db.notebookTagDao().assign(NotebookTagEntity(notebookId = "nb1", tagId = "legacy"))
        val fine = repo.createTag("fine")
        repo.assignTag("nb1", fine.id)

        repo.repairUnreadableColors()

        val byId = repo.observeTags().first().associateBy { it.id }
        assertEquals(TagColor.forName("legacy"), byId["legacy"]!!.colorArgb)
        assertTrue(ai.elrond.domain.TagColor.isReadable(byId["legacy"]!!.colorArgb))
        assertEquals(fine.colorArgb, byId[fine.id]!!.colorArgb) // untouched

        // Idempotent: a second run changes nothing.
        repo.repairUnreadableColors()
        assertEquals(TagColor.forName("legacy"), repo.observeTags().first().first { it.id == "legacy" }.colorArgb)
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
