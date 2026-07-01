package ai.elrond.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NoteRepositoryTest {

    private val notebookDao = mockk<NotebookDao>(relaxed = true)
    private val pageDao = mockk<NotePageDao>(relaxed = true)
    private val strokeDao = mockk<StrokeDao>(relaxed = true)
    private val aiNoteDao = mockk<AiNoteDao>(relaxed = true)
    private val editEventDao = mockk<PageEditEventDao>(relaxed = true)

    private val repository = NoteRepository(
        notebookDao = notebookDao,
        pageDao = pageDao,
        strokeDao = strokeDao,
        aiNoteDao = aiNoteDao,
        editEventDao = editEventDao,
        clock = { FIXED_TIME },
        newId = { "fixed-id" },
    )

    @Before
    fun setUp() {
        // Stub ink serialization (it touches ink natives, absent on the JVM) so the stroke-diff
        // tests exercise the append-vs-rewrite decision without building real Strokes.
        mockkObject(StrokeSerialization)
        every { StrokeSerialization.toEntity(any(), any(), any(), any(), any(), any()) } answers {
            StrokeEntity(
                id = secondArg(),
                pageId = thirdArg(),
                brushFamily = "pressure-pen",
                colorArgb = 0,
                brushSize = 1f,
                brushEpsilon = 0.1f,
                inputs = StrokeSerialization.encodeInputs(emptyList()),
                createdAt = 0L,
            )
        }
    }

    @After
    fun tearDown() = unmockkObject(StrokeSerialization)

    @Test
    fun `createNotebook inserts entity with generated id and timestamp`() = runTest {
        val slot = slot<NotebookEntity>()
        coEvery { notebookDao.insert(capture(slot)) } returns Unit

        val notebook = repository.createNotebook("Work")

        assertEquals("fixed-id", slot.captured.id)
        assertEquals("Work", slot.captured.name)
        assertEquals(FIXED_TIME, slot.captured.createdAt)
        assertEquals("fixed-id", notebook.id)
    }

    @Test
    fun `createPage without title uses timestamp-title convention`() = runTest {
        val slot = slot<NotePageEntity>()
        coEvery { pageDao.insert(capture(slot)) } returns Unit

        val page = repository.createPage(notebookId = "nb-1")

        assertNull(slot.captured.customTitle)
        assertEquals(FIXED_TIME, slot.captured.createdAt)
        assertEquals(FIXED_TIME, slot.captured.modifiedAt)
        assertEquals("nb-1", page.notebookId)
    }

    @Test
    fun `createPage with custom title stores it`() = runTest {
        val slot = slot<NotePageEntity>()
        coEvery { pageDao.insert(capture(slot)) } returns Unit

        repository.createPage(notebookId = "nb-1", customTitle = "Meeting notes")

        assertEquals("Meeting notes", slot.captured.customTitle)
    }

    @Test
    fun `createNote creates a notebook and its first page in it`() = runTest {
        val notebookSlot = slot<NotebookEntity>()
        val pageSlot = slot<NotePageEntity>()
        coEvery { notebookDao.insert(capture(notebookSlot)) } returns Unit
        coEvery { pageDao.insert(capture(pageSlot)) } returns Unit

        val page = repository.createNote()

        // The first page belongs to the freshly created notebook, and that page is returned.
        assertEquals(notebookSlot.captured.id, pageSlot.captured.notebookId)
        assertEquals(pageSlot.captured.id, page.id)
    }

    @Test
    fun `renamePage touches modifiedAt`() = runTest {
        repository.renamePage("page-1", "New title")

        coVerify { pageDao.rename("page-1", "New title", FIXED_TIME) }
    }

    @Test
    fun `saveStrokes with empty list is a no-op`() = runTest {
        repository.saveStrokes("page-1", emptyList())

        coVerify(exactly = 0) { strokeDao.insertAll(any()) }
        coVerify(exactly = 0) { pageDao.touch(any(), any()) }
        coVerify(exactly = 0) { editEventDao.insert(any()) }
    }

    @Test
    fun `replaceStrokes records an edit event for the page`() = runTest {
        val slot = slot<PageEditEventEntity>()
        coEvery { editEventDao.insert(capture(slot)) } returns Unit

        repository.replaceStrokes("page-1", emptyList())

        coVerify { editEventDao.insert(any()) }
        assertEquals("page-1", slot.captured.pageId)
        assertEquals(FIXED_TIME, slot.captured.editedAt)
    }

    @Test
    fun `replaceAiNotes rewrites the page's AI notes atomically`() = runTest {
        val slot = slot<List<AiNoteEntity>>()
        coEvery { aiNoteDao.replaceForPage(eq("page-1"), capture(slot)) } returns Unit

        repository.replaceAiNotes(
            "page-1",
            listOf(ai.elrond.domain.AiInkNote(id = "n1", text = "hi", x = 1f, y = 2f, widthPx = 300f)),
        )

        assertEquals(1, slot.captured.size)
        assertEquals("hi", slot.captured.single().text)
        assertEquals("page-1", slot.captured.single().pageId)
        assertEquals(300f, slot.captured.single().widthPx)
    }

    @Test
    fun `loadAiNotes maps entities to domain notes`() = runTest {
        coEvery { aiNoteDao.getForPage("page-1") } returns listOf(
            AiNoteEntity(id = "n1", pageId = "page-1", text = "answer", x = 5f, y = 6f, widthPx = 400f, heightPx = 80f, createdAt = 1L),
        )

        val notes = repository.loadAiNotes("page-1")

        assertEquals("answer", notes.single().text)
        assertEquals(400f, notes.single().widthPx)
        assertEquals(80f, notes.single().heightPx)
    }

    @Test
    fun `clearStrokes deletes and touches page`() = runTest {
        repository.clearStrokes("page-1")

        coVerify { strokeDao.deleteForPage("page-1") }
        coVerify { pageDao.touch("page-1", FIXED_TIME) }
    }

    @Test
    fun `ensureDefaultNotebook creates one on first launch`() = runTest {
        coEvery { notebookDao.first() } returns null
        val slot = slot<NotebookEntity>()
        coEvery { notebookDao.insert(capture(slot)) } returns Unit

        val notebook = repository.ensureDefaultNotebook()

        assertEquals(NoteRepository.DEFAULT_NOTEBOOK_NAME, slot.captured.name)
        assertEquals("fixed-id", notebook.id)
    }

    @Test
    fun `ensureDefaultNotebook reuses the existing notebook`() = runTest {
        val existing = NotebookEntity(id = "nb-existing", name = "My Notes", createdAt = 1L)
        coEvery { notebookDao.first() } returns existing

        val notebook = repository.ensureDefaultNotebook()

        assertEquals("nb-existing", notebook.id)
        coVerify(exactly = 0) { notebookDao.insert(any()) }
    }

    @Test
    fun `deletePage delegates to dao`() = runTest {
        repository.deletePage("page-1")

        coVerify { pageDao.deleteById("page-1") }
    }

    @Test
    fun `replaceStrokes rewrites page strokes atomically and touches page`() = runTest {
        repository.replaceStrokes("page-1", emptyList())

        coVerify { strokeDao.replaceForPage("page-1", emptyList()) }
        coVerify { pageDao.touch("page-1", FIXED_TIME) }
    }

    @Test
    fun `updateStrokes appends only new strokes when the change is purely additive`() = runTest {
        val a = ai.elrond.domain.CanvasStroke("a", mockk())
        val b = ai.elrond.domain.CanvasStroke("b", mockk())

        repository.updateStrokes("page-1", previous = listOf(a), current = listOf(a, b))

        // Only the new stroke is inserted; the page is NOT fully rewritten.
        val slot = slot<List<StrokeEntity>>()
        coVerify { strokeDao.insertAll(capture(slot)) }
        assertEquals(listOf("b"), slot.captured.map { it.id })
        coVerify(exactly = 0) { strokeDao.replaceForPage(any(), any()) }
    }

    @Test
    fun `updateStrokes fully rewrites the page when a stroke is removed`() = runTest {
        val a = ai.elrond.domain.CanvasStroke("a", mockk())
        val b = ai.elrond.domain.CanvasStroke("b", mockk())

        repository.updateStrokes("page-1", previous = listOf(a, b), current = listOf(a))

        coVerify { strokeDao.replaceForPage(eq("page-1"), any()) }
    }

    @Test
    fun `updateStrokes fully rewrites the page when a stroke's geometry was baked`() = runTest {
        // Same id, different (transformed) Stroke reference — e.g. a baked lasso move.
        val before = ai.elrond.domain.CanvasStroke("a", mockk())
        val after = before.copy(stroke = mockk())

        repository.updateStrokes("page-1", previous = listOf(before), current = listOf(after))

        coVerify { strokeDao.replaceForPage(eq("page-1"), any()) }
        coVerify(exactly = 0) { strokeDao.insertAll(any()) }
    }

    @Test
    fun `loadStrokePreview decodes stored points and normalizes them`() = runTest {
        val entity = StrokeEntity(
            id = "s1",
            pageId = "page-1",
            brushFamily = "pressure-pen",
            colorArgb = 0,
            brushSize = 4f,
            brushEpsilon = 0.1f,
            inputs = StrokeSerialization.encodeInputs(
                listOf(
                    SerializedStrokeInput(x = 100f, y = 200f, t = 0, pressure = 1f, tilt = 0f, orientation = 0f),
                    SerializedStrokeInput(x = 300f, y = 200f, t = 16, pressure = 1f, tilt = 0f, orientation = 0f),
                ),
            ),
            createdAt = 1L,
        )
        coEvery { strokeDao.getForPage("page-1") } returns listOf(entity)

        val preview = repository.loadStrokePreview("page-1")

        // 200-unit-wide horizontal stroke → normalized to 0..1 on x, 0 on y.
        assertEquals(listOf(listOf(0f to 0f, 1f to 0f)), preview)
    }

    companion object {
        private const val FIXED_TIME = 1_780_000_000_000
    }
}
