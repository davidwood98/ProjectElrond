package ai.elrond.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
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
 * Real Room SQL (Robolectric) for the Subjects feature (FA-16): subject CRUD + sortOrder, the
 * single-subject (file-explorer) note membership, cascade-delete of descendant subjects + their
 * memberships (notes survive), and reorder.
 */
@RunWith(RobolectricTestRunner::class)
class SubjectRepositoryTest {

    private lateinit var db: ElrondDatabase
    private lateinit var repo: SubjectRepository
    private val ids = AtomicInteger(0)

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = SubjectRepository(
            subjectDao = db.subjectDao(),
            noteSubjectDao = db.noteSubjectDao(),
            clock = { 0L },
            newId = { "s${ids.incrementAndGet()}" },
            colorIdGenerator = { 7 },
        )
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedNotebook(id: String) {
        db.notebookDao().insert(NotebookEntity(id = id, name = "N", createdAt = 0))
    }

    @Test
    fun `createSubject assigns sequential sortOrder per parent and a palette colour`() = runTest {
        val a = repo.createSubject(parentId = null, name = "A")
        val b = repo.createSubject(parentId = null, name = "B")
        val a1 = repo.createSubject(parentId = a.id, name = "A1")

        assertEquals(0L, a.sortOrder)
        assertEquals(1L, b.sortOrder) // next root sibling
        assertEquals(0L, a1.sortOrder) // first child of A
        assertEquals(7, a.colorId) // from the injected colour generator
    }

    @Test
    fun `rename and setColor update the subject`() = runTest {
        val s = repo.createSubject(null, "Old")
        repo.renameSubject(s.id, "New")
        repo.setColor(s.id, 12)

        val updated = repo.observeSubjects().first().single()
        assertEquals("New", updated.name)
        assertEquals(12, updated.colorId)
    }

    @Test
    fun `deleting a subject cascades to descendant subjects and their notebook memberships`() = runTest {
        seedNotebook("nb1")
        val parent = repo.createSubject(null, "Parent")
        val child = repo.createSubject(parent.id, "Child")
        repo.assignNote("nb1", child.id) // a notebook filed deep in the subtree

        repo.deleteSubject(parent.id)

        // Both subjects gone; the membership cascade-cleared, but the notebook itself survives.
        assertTrue(repo.observeSubjects().first().isEmpty())
        assertNull(repo.subjectForNotebook("nb1"))
        assertTrue(db.notebookDao().getById("nb1") != null)
    }

    @Test
    fun `a notebook files into at most one subject (reassign replaces, null unfiles)`() = runTest {
        seedNotebook("nb1")
        val s1 = repo.createSubject(null, "S1")
        val s2 = repo.createSubject(null, "S2")

        repo.assignNote("nb1", s1.id)
        assertEquals(s1.id, repo.subjectForNotebook("nb1"))

        repo.assignNote("nb1", s2.id) // reassign — single row replaced, not added
        assertEquals(s2.id, repo.subjectForNotebook("nb1"))
        assertEquals(mapOf("nb1" to s2.id), repo.observeNoteSubjects().first())

        repo.assignNote("nb1", null) // unfile
        assertNull(repo.subjectForNotebook("nb1"))
        assertTrue(repo.observeNoteSubjects().first().isEmpty())
    }

    @Test
    fun `deleting a notebook cascades its subject membership`() = runTest {
        seedNotebook("nb1")
        val s = repo.createSubject(null, "S")
        repo.assignNote("nb1", s.id)

        db.notebookDao().deleteById("nb1")

        assertTrue(repo.observeNoteSubjects().first().isEmpty())
    }

    @Test
    fun `reorder rewrites sortOrder to match the given order`() = runTest {
        val a = repo.createSubject(null, "A")
        val b = repo.createSubject(null, "B")
        val c = repo.createSubject(null, "C")

        repo.reorder(listOf(c.id, a.id, b.id))

        val byId = repo.observeSubjects().first().associateBy { it.id }
        assertEquals(0L, byId.getValue(c.id).sortOrder)
        assertEquals(1L, byId.getValue(a.id).sortOrder)
        assertEquals(2L, byId.getValue(b.id).sortOrder)
    }
}
