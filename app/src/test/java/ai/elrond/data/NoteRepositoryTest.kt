package ai.elrond.data

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoteRepositoryTest {

    private val notebookDao = mockk<NotebookDao>(relaxed = true)
    private val pageDao = mockk<NotePageDao>(relaxed = true)
    private val strokeDao = mockk<StrokeDao>(relaxed = true)

    private val repository = NoteRepository(
        notebookDao = notebookDao,
        pageDao = pageDao,
        strokeDao = strokeDao,
        clock = { FIXED_TIME },
        newId = { "fixed-id" },
    )

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
    fun `renamePage touches modifiedAt`() = runTest {
        repository.renamePage("page-1", "New title")

        coVerify { pageDao.rename("page-1", "New title", FIXED_TIME) }
    }

    @Test
    fun `saveStrokes with empty list is a no-op`() = runTest {
        repository.saveStrokes("page-1", emptyList())

        coVerify(exactly = 0) { strokeDao.insertAll(any()) }
        coVerify(exactly = 0) { pageDao.touch(any(), any()) }
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
    fun `loadStrokePreview decodes stored points and normalizes them`() = runTest {
        val entity = StrokeEntity(
            id = "s1",
            pageId = "page-1",
            brushFamily = "pressure-pen",
            colorArgb = 0,
            brushSize = 4f,
            brushEpsilon = 0.1f,
            inputsJson = """[
                {"x":100.0,"y":200.0,"t":0,"pressure":1.0,"tilt":0.0,"orientation":0.0},
                {"x":300.0,"y":200.0,"t":16,"pressure":1.0,"tilt":0.0,"orientation":0.0}
            ]""",
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
