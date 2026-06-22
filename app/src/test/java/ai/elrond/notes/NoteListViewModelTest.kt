package ai.elrond.notes

import ai.elrond.domain.Notebook
import ai.elrond.domain.NotePage
import ai.elrond.presentation.NoteListViewModel
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

    private fun page(id: String, lastOpenedAt: Long = 0L) = NotePage(
        id = id,
        notebookId = "nb-1",
        customTitle = null,
        createdAt = 1L,
        modifiedAt = 2L,
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
    fun `recentNotes keeps only notes opened in the last 24h, most recent first`() = runTest(dispatcher) {
        val now = System.currentTimeMillis()
        val recentNew = page("recent-new", lastOpenedAt = now)
        val recentOld = page("recent-old", lastOpenedAt = now - 60_000L) // a minute ago
        val stale = page("stale", lastOpenedAt = now - 48L * 60 * 60 * 1000) // 2 days ago
        val never = page("never", lastOpenedAt = 0L)
        every { repository.observeTimeline() } returns
            flowOf(listOf(recentOld, stale, recentNew, never))
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        backgroundScope.launch { viewModel.recentNotes.collect { } }
        advanceUntilIdle()

        // Only the two within 24h, ordered most-recently-opened first; stale + never excluded.
        assertEquals(listOf("recent-new", "recent-old"), viewModel.recentNotes.value.map { it.id })
    }

    @Test
    fun `createNote bootstraps default notebook and reports new page id`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(emptyList())
        coEvery { repository.ensureDefaultNotebook() } returns Notebook("nb-1", "My Notes", 1L)
        coEvery { repository.createPage("nb-1") } returns page("new-page")
        val viewModel = NoteListViewModel(repository, thumbnailCache)

        var openedId: String? = null
        viewModel.createNote { openedId = it }
        advanceUntilIdle()

        assertEquals("new-page", openedId)
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
