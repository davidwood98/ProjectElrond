package ai.elrond.canvas

import ai.elrond.data.NoteRepository
import ai.elrond.data.SearchRepository
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.domain.NotePage
import ai.elrond.domain.SearchHighlight
import ai.elrond.domain.SelectionBounds
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ai.elrond.presentation.CanvasViewModel

/** FA-24c on-canvas search-result mode: the CanvasViewModel state machine (active gating, highlights, jump, exit). */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSearchTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun page(id: String, notebookId: String, pageNumber: Int = 1) =
        NotePage(id = id, notebookId = notebookId, customTitle = null, createdAt = 0, modifiedAt = 0, pageNumber = pageNumber)

    private fun repo(pageId: String, notebookId: String, pages: List<NotePage>): NoteRepository =
        mockk(relaxed = true) {
            every { loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
            coEvery { getPage(pageId) } returns page(pageId, notebookId)
            coEvery { loadAiNotes(any()) } returns emptyList()
            every { observePagesOrdered(notebookId) } returns flowOf(pages)
        }

    private fun vm(
        searchRepository: SearchRepository,
        searchNb: MutableStateFlow<String?>,
        searchQuery: MutableStateFlow<String>,
        repository: NoteRepository,
        pageId: String,
        landingPending: Boolean = false,
        onClear: () -> Unit = {},
        onSet: (String, String) -> Unit = { _, _ -> },
    ): CanvasViewModel {
        var landing = landingPending
        return CanvasViewModel(
            repository = repository,
            searchRepository = searchRepository,
            searchModeNotebookIdFlow = searchNb,
            searchModeQueryFlow = searchQuery,
            clearSearchModePref = { onClear() },
            setSearchModePref = { nb, q -> onSet(nb, q) },
            consumeSearchLanding = { val p = landing; landing = false; p }, // one-shot, like production
            pageId = pageId,
            strokeBoundsOf = { SelectionBounds(0f, 0f, 10f, 10f) },
            strokeTransformer = { stroke, _ -> stroke },
            centroidOf = { GestureTriggerDetector.Point(0f, 0f) },
        )
    }

    @Test
    fun `content match on this page shows highlights and activates the pill`() = runTest(dispatcher) {
        val search = mockk<SearchRepository> {
            coEvery { pageHighlights("p1", "kinematics") } returns listOf(SearchHighlight(0f, 5f, 10f, 15f))
            coEvery { matchingPageIds("nb1", "kinematics") } returns setOf("p1")
        }
        val v = vm(search, MutableStateFlow("nb1"), MutableStateFlow("kinematics"), repo("p1", "nb1", listOf(page("p1", "nb1"))), "p1")
        advanceUntilIdle()

        assertTrue(v.searchModeActive.value)
        assertEquals(1, v.searchHighlights.value.size)
    }

    @Test
    fun `a title or tag only match (no content anywhere) shows no highlight mode`() = runTest(dispatcher) {
        val search = mockk<SearchRepository> {
            coEvery { pageHighlights(any(), any()) } returns emptyList()
            coEvery { matchingPageIds(any(), any()) } returns emptySet()
        }
        val v = vm(search, MutableStateFlow("nb1"), MutableStateFlow("physics"), repo("p1", "nb1", listOf(page("p1", "nb1"))), "p1")
        advanceUntilIdle()

        assertFalse(v.searchModeActive.value)
        assertTrue(v.searchHighlights.value.isEmpty())
    }

    @Test
    fun `search mode for a different notebook does not activate on this page`() = runTest(dispatcher) {
        val search = mockk<SearchRepository>(relaxed = true)
        val v = vm(search, MutableStateFlow("other-nb"), MutableStateFlow("kinematics"), repo("p1", "nb1", listOf(page("p1", "nb1"))), "p1")
        advanceUntilIdle()

        assertFalse(v.searchModeActive.value)
    }

    @Test
    fun `initial landing on a no-match page jumps to the single matching page`() = runTest(dispatcher) {
        val pages = listOf(page("p1", "nb1", pageNumber = 1), page("p2", "nb1", pageNumber = 2))
        val search = mockk<SearchRepository> {
            coEvery { pageHighlights("p1", "kinematics") } returns emptyList()
            coEvery { matchingPageIds("nb1", "kinematics") } returns setOf("p2")
        }
        val v = vm(search, MutableStateFlow("nb1"), MutableStateFlow("kinematics"), repo("p1", "nb1", pages), "p1", landingPending = true)

        var turned: String? = null
        val job = launch { turned = v.pageTurnEvents.first() }
        advanceUntilIdle()
        job.cancel()

        assertEquals("p2", turned)
    }

    @Test
    fun `without a pending landing a no-match page does NOT jump (the bounce bug)`() = runTest(dispatcher) {
        // The regression: page-turning to a non-matching page must not bounce back to the match.
        val pages = listOf(page("p1", "nb1", pageNumber = 1), page("p2", "nb1", pageNumber = 2))
        val search = mockk<SearchRepository> {
            coEvery { pageHighlights("p1", "kinematics") } returns emptyList()
            coEvery { matchingPageIds("nb1", "kinematics") } returns setOf("p2")
        }
        val v = vm(search, MutableStateFlow("nb1"), MutableStateFlow("kinematics"), repo("p1", "nb1", pages), "p1", landingPending = false)

        var turned: String? = null
        val job = launch { turned = v.pageTurnEvents.first() }
        advanceUntilIdle()
        job.cancel()

        assertEquals(null, turned) // no jump; the pill still shows so the user can browse freely
        assertTrue(v.searchModeActive.value)
    }

    @Test
    fun `a multi-page landing opens the Pages menu instead of jumping`() = runTest(dispatcher) {
        val pages = listOf(page("p1", "nb1", 1), page("p2", "nb1", 2), page("p3", "nb1", 3))
        val search = mockk<SearchRepository> {
            coEvery { pageHighlights("p1", "kinematics") } returns emptyList()
            coEvery { matchingPageIds("nb1", "kinematics") } returns setOf("p2", "p3")
        }
        val v = vm(search, MutableStateFlow("nb1"), MutableStateFlow("kinematics"), repo("p1", "nb1", pages), "p1", landingPending = true)

        var pagesOpened = false
        var turned: String? = null
        val jobPages = launch { v.openSearchPagesEvents.first(); pagesOpened = true }
        val jobTurn = launch { turned = v.pageTurnEvents.first() }
        advanceUntilIdle()
        jobPages.cancel(); jobTurn.cancel()

        assertTrue(pagesOpened)
        assertEquals(null, turned) // multi-page → menu, not a jump
        assertEquals(setOf("p2", "p3"), v.searchMatchingPageIds.value)
    }

    @Test
    fun `exitSearchMode clears the persisted pref`() = runTest(dispatcher) {
        var cleared = false
        val search = mockk<SearchRepository>(relaxed = true)
        val v = vm(search, MutableStateFlow("nb1"), MutableStateFlow("kinematics"), repo("p1", "nb1", listOf(page("p1", "nb1"))), "p1", onClear = { cleared = true })
        advanceUntilIdle()

        v.exitSearchMode()
        advanceUntilIdle()
        assertTrue(cleared)
    }

    @Test
    fun `searchThisNotebook persists the current notebook and query`() = runTest(dispatcher) {
        var set: Pair<String, String>? = null
        val search = mockk<SearchRepository>(relaxed = true)
        val v = vm(search, MutableStateFlow(null), MutableStateFlow(""), repo("p1", "nb1", listOf(page("p1", "nb1"))), "p1", onSet = { nb, q -> set = nb to q })
        advanceUntilIdle()

        v.searchThisNotebook("  forward kinematics  ")
        advanceUntilIdle()
        assertEquals("nb1" to "forward kinematics", set)
    }
}
