package ai.elrond.notes

import ai.elrond.domain.NotePage
import ai.elrond.domain.NotebookSummary
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.data.SessionNotesTracker
import ai.elrond.data.ThumbnailCache
import ai.elrond.data.NoteRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteListViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<NoteRepository>(relaxed = true)
    private val thumbnailCache = mockk<ThumbnailCache>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun page(id: String, lastOpenedAt: Long = 0L, notebookId: String = "nb-1") = NotePage(
        id = id,
        notebookId = notebookId,
        customTitle = null,
        createdAt = 1L,
        modifiedAt = 2L,
        lastOpenedAt = lastOpenedAt,
    )

    private fun summary(notebookId: String, lastOpenedAt: Long = 0L) = NotebookSummary(
        notebookId = notebookId,
        title = notebookId,
        coverPageId = "$notebookId-p1",
        pageCount = 1,
        modifiedAt = 2L,
        lastViewedPageId = "$notebookId-p1",
        lastOpenedAt = lastOpenedAt,
    )

    @Test
    fun `pages exposes the repository timeline`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(listOf(page("p1"), page("p2")))
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        backgroundScope.launch { viewModel.pages.collect { } }
        advanceUntilIdle()

        assertEquals(listOf("p1", "p2"), viewModel.pages.value.map { it.id })
    }

    @Test
    fun `recentNotebooks keeps only notebooks opened in the last 24h, most recent first`() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        every { repository.observeNotebookSummaries() } returns flowOf(
            listOf(
                summary("recent-old", lastOpenedAt = now - 60_000L), // a minute ago
                summary("stale", lastOpenedAt = now - 48L * 60 * 60 * 1000), // 2 days ago
                summary("recent-new", lastOpenedAt = now),
                summary("never", lastOpenedAt = 0L),
            ),
        )
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        backgroundScope.launch { viewModel.recentNotebooks.collect { } }
        advanceUntilIdle()

        // Only the two within 24h, ordered most-recently-opened first; stale + never excluded.
        assertEquals(listOf("recent-new", "recent-old"), viewModel.recentNotebooks.value.map { it.notebookId })
    }

    @Test
    fun `createNote creates a note and reports the new page id`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(emptyList())
        coEvery { repository.createNote() } returns page("new-page")
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        var openedId: String? = null
        viewModel.createNote { openedId = it }
        advanceUntilIdle()

        assertEquals("new-page", openedId)
    }

    @Test
    fun `sessionNotebooks maps tracked pages to their notebooks, de-duped in open order`() = runTest(dispatcher) {
        // p1 & p3 belong to nbB, p2 to nbA. Opening p1, p2, p3 → tabs [nbB, nbA] (open order, deduped).
        every { repository.observeTimeline() } returns flowOf(
            listOf(page("p1", notebookId = "nbB"), page("p2", notebookId = "nbA"), page("p3", notebookId = "nbB")),
        )
        every { repository.observeNotebookSummaries() } returns flowOf(listOf(summary("nbB"), summary("nbA")))
        val tracker = SessionNotesTracker()
        tracker.recordOpened("p1") // nbB first
        tracker.recordOpened("p2") // nbA
        tracker.recordOpened("p3") // nbB again → no new tab
        val viewModel = NoteListViewModel(repository, thumbnailCache, tracker)

        backgroundScope.launch { viewModel.sessionNotebooks.collect { } }
        advanceUntilIdle()

        assertEquals(listOf("nbB", "nbA"), viewModel.sessionNotebooks.value.map { it.notebookId })
    }

    @Test
    fun `renameNote renames the notebook (title survives page reorders)`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(emptyList())
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        // The arg is now the notebookId; blank handling lives in repository.renameNotebook.
        viewModel.renameNote("nb1", "Maths")
        viewModel.renameNote("nb2", "   ")
        advanceUntilIdle()

        coVerify { repository.renameNotebook("nb1", "Maths") }
        coVerify { repository.renameNotebook("nb2", "   ") }
    }

    @Test
    fun `deleteNote delegates to repository and drops the cached thumbnail`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(emptyList())
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        viewModel.deleteNote("p1")
        advanceUntilIdle()

        coVerify { repository.deletePage("p1") }
        verify { thumbnailCache.delete("p1") }
    }
}
