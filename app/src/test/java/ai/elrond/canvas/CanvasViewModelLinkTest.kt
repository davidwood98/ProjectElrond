package ai.elrond.canvas

import ai.elrond.data.BacklinkRow
import ai.elrond.data.NotebookLinkRepository
import ai.elrond.data.NoteRepository
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.domain.LiveTransform
import ai.elrond.domain.Notebook
import ai.elrond.domain.NotebookLink
import ai.elrond.domain.NotebookSummary
import ai.elrond.domain.NotePage
import ai.elrond.domain.SelectionBounds
import ai.elrond.presentation.CanvasViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Notebook link boxes in [CanvasViewModel] (FA-24): create/tap/hold/redefine/delete, the unified
 * selection pipeline, and the HistorySnapshot undo path (links from day one — the FA-21 lesson).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelLinkTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun p(x: Float, y: Float) = GestureTriggerDetector.Point(x, y)

    private fun summary(id: String = "nb-target", title: String = "Target notebook") =
        NotebookSummary(
            notebookId = id,
            title = title,
            coverPageId = "$id-p1",
            pageCount = 1,
            modifiedAt = 0L,
            lastViewedPageId = "$id-p1",
            lastOpenedAt = 0L,
        )

    private fun linkRepo(): NotebookLinkRepository {
        var counter = 0
        return mockk<NotebookLinkRepository>(relaxed = true) {
            every { newLinkId() } answers { "link-${counter++}" }
            every { now() } returns 1_000L
            coEvery { loadForPage(any()) } returns emptyList()
        }
    }

    private fun viewModel(
        linkRepository: NotebookLinkRepository? = linkRepo(),
        repository: NoteRepository? = null,
        pageId: String? = null,
    ) = CanvasViewModel(
        repository = repository,
        linkRepository = linkRepository,
        pageId = pageId,
        strokeBoundsOf = { SelectionBounds(0f, 0f, 10f, 10f) },
        strokeTransformer = { stroke, _ -> stroke },
        centroidOf = { p(0f, 0f) },
    )

    @Test
    fun `createLink places a selected link box at the default position and is undoable`() {
        val vm = viewModel()
        vm.createLink(summary())

        val link = vm.links.value.single()
        assertEquals("nb-target", link.targetNotebookId)
        assertEquals("Target notebook", link.linkText)
        assertEquals(NotebookLink.DEFAULT_WIDTH_PX, link.widthPx, 0f)
        assertEquals(1_000L, link.createdAt)
        // Auto-selected as a lone link.
        assertEquals(setOf(link.id), vm.selection.value?.linkIds)
        assertTrue(vm.selection.value?.isSingleLink == true)

        vm.undo()
        assertTrue(vm.links.value.isEmpty())
        vm.redo()
        assertEquals(1, vm.links.value.size)
    }

    @Test
    fun `tapLink on a healthy unselected link emits the target's last viewed page`() = runTest(dispatcher) {
        val repository = mockk<NoteRepository>(relaxed = true)
        coEvery { repository.lastViewedPageId("nb-target") } returns "nb-target-p1"
        val vm = viewModel(repository = repository)
        vm.createLink(summary())
        val linkId = vm.links.value.single().id
        vm.clearSelection()

        var opened: String? = null
        val job = launch { opened = vm.openLinkEvents.first() }
        vm.tapLink(linkId)
        advanceUntilIdle()
        job.cancel()

        assertEquals("nb-target-p1", opened)
    }

    @Test
    fun `tapLink falls back to another live page when the last viewed page was deleted`() = runTest(dispatcher) {
        // The DAO query only sees live rows — the repository seam returns the surviving page.
        val repository = mockk<NoteRepository>(relaxed = true)
        coEvery { repository.lastViewedPageId("nb-target") } returns "nb-target-p2"
        val vm = viewModel(repository = repository)
        vm.createLink(summary())
        val linkId = vm.links.value.single().id
        vm.clearSelection()

        var opened: String? = null
        val job = launch { opened = vm.openLinkEvents.first() }
        vm.tapLink(linkId)
        advanceUntilIdle()
        job.cancel()

        assertEquals("nb-target-p2", opened)
    }

    @Test
    fun `tapLink on an empty notebook shows a transient message and never emits`() = runTest(dispatcher) {
        val repository = mockk<NoteRepository>(relaxed = true)
        coEvery { repository.lastViewedPageId("nb-target") } returns null
        val vm = viewModel(repository = repository)
        vm.createLink(summary())
        val linkId = vm.links.value.single().id
        vm.clearSelection()

        var opened: String? = null
        val job = launch { opened = vm.openLinkEvents.first() }
        vm.tapLink(linkId)
        // runCurrent (not advanceUntilIdle): the transient message auto-clears after 1.5s of
        // virtual time, so advancing to idle would blow past it.
        runCurrent()

        assertNull(opened)
        assertNotNull(vm.transientMessage.value)
        job.cancel()
    }

    @Test
    fun `tapLink no-ops while the link is selected`() = runTest(dispatcher) {
        val repository = mockk<NoteRepository>(relaxed = true)
        coEvery { repository.lastViewedPageId(any()) } returns "some-page"
        val vm = viewModel(repository = repository)
        vm.createLink(summary()) // createLink leaves the link selected

        var opened: String? = null
        val job = launch { opened = vm.openLinkEvents.first() }
        vm.tapLink(vm.links.value.single().id)
        advanceUntilIdle()

        assertNull(opened)
        job.cancel()
    }

    @Test
    fun `holdLink selects a healthy link and surfaces the menu for a broken one`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.createLink(summary())
        val healthy = vm.links.value.single().id
        vm.clearSelection()

        vm.holdLink(healthy)
        assertEquals(setOf(healthy), vm.selection.value?.linkIds)

        // Break the link in place (as the FK SET_NULL would on reload) via redefine + copy.
        vm.clearSelection()
        vm.deleteLink(healthy)
        // Simulate a loaded broken link: create then break through the redefine path is not
        // possible (redefine always sets a target), so drive holdLink against a broken row loaded
        // from storage.
        val broken = NotebookLink(
            id = "broken-1",
            targetNotebookId = null,
            x = 0f,
            y = 0f,
            widthPx = NotebookLink.DEFAULT_WIDTH_PX,
            linkText = "Old title",
            createdAt = 0L,
        )
        val linkRepository = mockk<NotebookLinkRepository>(relaxed = true)
        coEvery { linkRepository.loadForPage("page-1") } returns listOf(broken)
        val repository = mockk<NoteRepository>(relaxed = true)
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
        coEvery { repository.loadAiNotes("page-1") } returns emptyList()
        val vm2 = viewModel(linkRepository = linkRepository, repository = repository, pageId = "page-1")
        advanceUntilIdle()

        var menuFor: String? = null
        val job = launch { menuFor = vm2.brokenLinkMenuEvents.first() }
        // The menu event is tryEmit-ted synchronously with no replay, so the collector must be
        // subscribed BEFORE holdLink fires.
        runCurrent()
        vm2.holdLink("broken-1")
        advanceUntilIdle()

        assertEquals("broken-1", menuFor)
        assertNull(vm2.selection.value) // a broken link never enters the selection on hold
        job.cancel()
    }

    @Test
    fun `redefineLink re-points the target and label in place, undoably`() {
        val vm = viewModel()
        vm.createLink(summary())
        val id = vm.links.value.single().id

        vm.redefineLink(id, summary(id = "nb-other", title = "Other"))
        val redefined = vm.links.value.single()
        assertEquals("nb-other", redefined.targetNotebookId)
        assertEquals("Other", redefined.linkText)
        assertEquals(id, redefined.id) // in place — same row

        vm.undo()
        assertEquals("nb-target", vm.links.value.single().targetNotebookId)
    }

    @Test
    fun `deleteLink removes the box and undo restores it`() {
        val vm = viewModel()
        vm.createLink(summary())
        val id = vm.links.value.single().id

        vm.deleteLink(id)
        assertTrue(vm.links.value.isEmpty())
        assertNull(vm.selection.value)

        vm.undo()
        assertEquals(id, vm.links.value.single().id)
    }

    @Test
    fun `a lasso selects a link box by its centre and a commit moves it`() {
        val vm = viewModel()
        vm.createLink(summary())
        val id = vm.links.value.single().id
        vm.clearSelection()

        // Default position with no canvas size = (0,0), 280x56 — a 300px lasso encloses its centre.
        vm.selectByLasso(listOf(p(0f, 0f), p(300f, 0f), p(300f, 300f), p(0f, 300f)))
        assertEquals(setOf(id), vm.selection.value?.linkIds)

        vm.previewTransform(LiveTransform(dx = 40f, dy = 25f))
        vm.commitTransform()
        val moved = vm.links.value.single()
        assertEquals(40f, moved.x, 0.001f)
        assertEquals(25f, moved.y, 0.001f)

        // A move never resizes the box.
        assertEquals(NotebookLink.DEFAULT_WIDTH_PX, moved.widthPx, 0f)

        vm.undo()
        assertEquals(0f, vm.links.value.single().x, 0.001f)
    }

    @Test
    fun `scaling a selected link grows its width`() {
        val vm = viewModel()
        vm.createLink(summary())

        vm.previewTransform(LiveTransform(scaleX = 2f, scaleY = 2f))
        vm.commitTransform()
        assertEquals(NotebookLink.DEFAULT_WIDTH_PX * 2f, vm.links.value.single().widthPx, 0.001f)
    }

    @Test
    fun `duplicate, copy-paste and cut carry link boxes`() {
        val vm = viewModel()
        vm.createLink(summary())
        val original = vm.links.value.single()

        vm.duplicateSelection()
        assertEquals(2, vm.links.value.size)
        val copy = vm.links.value.first { it.id != original.id }
        assertEquals(original.createdAt, copy.createdAt) // creation stamp preserved on a clone
        assertEquals(original.targetNotebookId, copy.targetNotebookId)

        // Cut the duplicate (it's the current selection), then paste it back elsewhere.
        vm.cutSelection()
        assertEquals(1, vm.links.value.size)
        assertTrue(vm.clipboard.value.active)
        vm.pasteAt(500f, 500f)
        assertEquals(2, vm.links.value.size)
        assertTrue(vm.links.value.any { it.x == 500f && it.y == 500f })

        // The whole cut+paste round trip is undoable step by step.
        vm.undo() // un-paste
        assertEquals(1, vm.links.value.size)
        vm.undo() // un-cut
        assertEquals(2, vm.links.value.size)
    }

    @Test
    fun `deleteSelection removes a mixed selection including the link`() {
        val vm = viewModel()
        vm.createLink(summary())
        assertEquals(1, vm.links.value.size)

        vm.deleteSelection()
        assertTrue(vm.links.value.isEmpty())

        vm.undo()
        assertEquals(1, vm.links.value.size)
    }

    @Test
    fun `clearPage clears link boxes undoably`() {
        val vm = viewModel()
        vm.createLink(summary())

        vm.clearPage()
        assertTrue(vm.links.value.isEmpty())

        vm.undo()
        assertEquals(1, vm.links.value.size)
    }

    @Test
    fun `observeBacklinks resolves source titles with an Untitled fallback`() = runTest(dispatcher) {
        val page = NotePage(
            id = "page-1",
            notebookId = "nb-1",
            customTitle = null,
            createdAt = 0L,
            modifiedAt = 0L,
        )
        val repository = mockk<NoteRepository>(relaxed = true)
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
        coEvery { repository.loadAiNotes("page-1") } returns emptyList()
        coEvery { repository.getPage("page-1") } returns page
        every { repository.observePagesOrdered("nb-1") } returns flowOf(listOf(page))
        every { repository.observeNotebook("nb-1") } returns flowOf(null as Notebook?)
        every { repository.observeNotebookSummaries() } returns flowOf(
            listOf(summary(id = "nb-src", title = "Source notebook")),
        )
        val linkRepository = mockk<NotebookLinkRepository>(relaxed = true)
        coEvery { linkRepository.loadForPage("page-1") } returns emptyList()
        every { linkRepository.observeBacklinks("nb-1") } returns flowOf(
            listOf(
                BacklinkRow(id = "l1", sourcePageId = "src-p1", sourceNotebookId = "nb-src", createdAt = 2L),
                BacklinkRow(id = "l2", sourcePageId = "src-p2", sourceNotebookId = "nb-unknown", createdAt = 1L),
            ),
        )

        val vm = viewModel(linkRepository = linkRepository, repository = repository, pageId = "page-1")
        advanceUntilIdle()

        val backlinks = vm.observeBacklinks().first()
        assertEquals(listOf("l1", "l2"), backlinks.map { it.linkId })
        assertEquals("Source notebook", backlinks[0].sourceTitle)
        assertEquals("src-p1", backlinks[0].sourcePageId)
        assertEquals("Untitled", backlinks[1].sourceTitle)
    }
}
