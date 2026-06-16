package ai.elrond.data

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
 * Real Room SQL on an in-memory database (Robolectric) — complements the mock-DAO
 * repository tests by exercising actual schema behaviour the mocks can't: foreign-key
 * cascades, the page_edit_events one-row-per-day dedup, and the calendar-suggestion filter.
 */
@RunWith(RobolectricTestRunner::class)
class RoomDaoTest {

    private lateinit var db: ElrondDatabase
    private lateinit var notebookDao: NotebookDao
    private lateinit var pageDao: NotePageDao
    private lateinit var strokeDao: StrokeDao
    private lateinit var aiNoteDao: AiNoteDao
    private lateinit var editEventDao: PageEditEventDao
    private lateinit var todoDao: TodoDao
    private lateinit var calendarDao: CalendarEventDao

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notebookDao = db.notebookDao()
        pageDao = db.notePageDao()
        strokeDao = db.strokeDao()
        aiNoteDao = db.aiNoteDao()
        editEventDao = db.pageEditEventDao()
        todoDao = db.todoDao()
        calendarDao = db.calendarEventDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seedPage(id: String = "p1") {
        notebookDao.insert(NotebookEntity(id = "nb1", name = "N", createdAt = 0))
        pageDao.insert(
            NotePageEntity(id = id, notebookId = "nb1", customTitle = null, createdAt = 0, modifiedAt = 0),
        )
    }

    @Test
    fun `page_edit_events dedups to one row per page per day`() = runTest {
        seedPage()
        editEventDao.insert(PageEditEventEntity(id = "e1", pageId = "p1", editDay = 100, editedAt = 1))
        // Same (pageId, editDay): the unique index + INSERT-IGNORE drops this duplicate.
        editEventDao.insert(PageEditEventEntity(id = "e2", pageId = "p1", editDay = 100, editedAt = 2))
        editEventDao.insert(PageEditEventEntity(id = "e3", pageId = "p1", editDay = 101, editedAt = 3))

        val rows = editEventDao.observeAll().first()
        assertEquals(2, rows.size)
        assertEquals(setOf(100L, 101L), rows.map { it.editDay }.toSet())
    }

    @Test
    fun `deleting a page cascades to its edit events strokes and ai notes`() = runTest {
        seedPage()
        editEventDao.insert(PageEditEventEntity(id = "e1", pageId = "p1", editDay = 1, editedAt = 1))
        strokeDao.insertAll(listOf(stroke("s1")))
        aiNoteDao.insertAll(
            listOf(AiNoteEntity(id = "a1", pageId = "p1", text = "x", x = 0f, y = 0f, widthPx = 1f, createdAt = 0)),
        )

        pageDao.deleteById("p1")

        assertTrue(editEventDao.observeAll().first().isEmpty())
        assertTrue(strokeDao.getForPage("p1").isEmpty())
        assertTrue(aiNoteDao.getForPage("p1").isEmpty())
    }

    @Test
    fun `observeSuggested returns only unconfirmed AI suggestions`() = runTest {
        calendarDao.insert(event("c1", "Suggested", aiSuggested = true, confirmed = false))
        calendarDao.insert(event("c2", "Confirmed", aiSuggested = true, confirmed = true))
        calendarDao.insert(event("c3", "Manual", aiSuggested = false, confirmed = false))

        assertEquals(listOf("Suggested"), calendarDao.observeSuggested().first().map { it.title })
    }

    @Test
    fun `replaceForPage atomically swaps a page's strokes`() = runTest {
        seedPage()
        strokeDao.insertAll(listOf(stroke("s1"), stroke("s2")))

        strokeDao.replaceForPage("p1", listOf(stroke("s3")))

        assertEquals(listOf("s3"), strokeDao.getForPage("p1").map { it.id })
    }

    @Test
    fun `stroke groupId round-trips through the dao`() = runTest {
        seedPage()
        strokeDao.insertAll(
            listOf(
                stroke("s1").copy(groupId = "g1"),
                stroke("s2").copy(groupId = "g1"),
                stroke("s3"),
            ),
        )

        val rows = strokeDao.getForPage("p1").associateBy { it.id }
        assertEquals("g1", rows.getValue("s1").groupId)
        assertEquals("g1", rows.getValue("s2").groupId)
        assertNull(rows.getValue("s3").groupId)
    }

    @Test
    fun `todo allContents returns every title for dedup`() = runTest {
        todoDao.insert(TodoItemEntity(id = "t1", title = "Buy milk", createdAt = 0))
        todoDao.insert(TodoItemEntity(id = "t2", title = "Call bank", createdAt = 0))

        assertEquals(setOf("Buy milk", "Call bank"), todoDao.allContents().toSet())
    }

    private fun stroke(id: String) = StrokeEntity(
        id = id, pageId = "p1", brushFamily = "pressure-pen", colorArgb = 0,
        brushSize = 4f, brushEpsilon = 0.1f, inputsJson = "[]", createdAt = 0,
    )

    private fun event(id: String, title: String, aiSuggested: Boolean, confirmed: Boolean) =
        CalendarEventEntity(
            id = id, title = title, startTime = 10, endTime = 20,
            isAiSuggested = aiSuggested, isConfirmed = confirmed, createdAt = 0,
        )
}
