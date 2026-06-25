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

    private suspend fun seedPage(id: String) {
        if (db.notebookDao().first() == null) {
            db.notebookDao().insert(NotebookEntity(id = "nb1", name = "N", createdAt = 0))
        }
        db.notePageDao().insert(
            NotePageEntity(id = id, notebookId = "nb1", customTitle = null, createdAt = 0, modifiedAt = 0),
        )
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
    fun `deleting a subject cascades to descendant subjects and their note memberships`() = runTest {
        seedPage("p1")
        val parent = repo.createSubject(null, "Parent")
        val child = repo.createSubject(parent.id, "Child")
        repo.assignNote("p1", child.id) // a note filed deep in the subtree

        repo.deleteSubject(parent.id)

        // Both subjects gone; the note's membership cascade-cleared, but the note itself survives.
        assertTrue(repo.observeSubjects().first().isEmpty())
        assertNull(repo.subjectForNote("p1"))
        assertTrue(db.notePageDao().getById("p1") != null)
    }

    @Test
    fun `a note files into at most one subject (reassign replaces, null unfiles)`() = runTest {
        seedPage("p1")
        val s1 = repo.createSubject(null, "S1")
        val s2 = repo.createSubject(null, "S2")

        repo.assignNote("p1", s1.id)
        assertEquals(s1.id, repo.subjectForNote("p1"))

        repo.assignNote("p1", s2.id) // reassign — single row replaced, not added
        assertEquals(s2.id, repo.subjectForNote("p1"))
        assertEquals(mapOf("p1" to s2.id), repo.observeNoteSubjects().first())

        repo.assignNote("p1", null) // unfile
        assertNull(repo.subjectForNote("p1"))
        assertTrue(repo.observeNoteSubjects().first().isEmpty())
    }

    @Test
    fun `deleting a note cascades its subject membership`() = runTest {
        seedPage("p1")
        val s = repo.createSubject(null, "S")
        repo.assignNote("p1", s.id)

        db.notePageDao().deleteById("p1")

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
