package ai.elrond.canvas

import ai.elrond.ai.GestureTriggerDetector
import ai.elrond.ai.HandwritingRecognizer
import ai.elrond.ai.NotePosition
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import ai.elrond.data.NoteRepository
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** FA-9 lasso selection behaviour, with mockk strokes and fake ink seams. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSelectionTest {

    private val dispatcher = StandardTestDispatcher()

    // Three strokes at distinct centroids; a small box selects only the first two.
    private val s1 = mockk<Stroke>()
    private val s2 = mockk<Stroke>()
    private val s3 = mockk<Stroke>()
    private val centroids = mapOf(
        s1 to GestureTriggerDetector.Point(10f, 10f),
        s2 to GestureTriggerDetector.Point(20f, 20f),
        s3 to GestureTriggerDetector.Point(500f, 500f),
    )

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun p(x: Float, y: Float) = GestureTriggerDetector.Point(x, y)
    private fun square(size: Float) = listOf(p(0f, 0f), p(size, 0f), p(size, size), p(0f, size))

    private fun viewModel(
        recognizer: HandwritingRecognizer? = null,
        provider: AIProvider? = null,
        repository: NoteRepository? = null,
        enqueue: ((String) -> Unit)? = null,
    ) = CanvasViewModel(
        recognizer = recognizer,
        aiProvider = provider,
        lineSplitter = { listOf(it) },
        notePlacer = { NotePosition(0f, 0f) },
        repository = repository,
        pageId = if (repository != null) "page-1" else null,
        enqueueExtraction = enqueue,
        centroidOf = { centroids[it] ?: p(0f, 0f) },
        // Geometry-free fakes: keep the stroke object, report a fixed box (ink natives untouched).
        strokeTransformer = { stroke, _ -> stroke },
        strokeBoundsOf = { SelectionBounds(0f, 0f, 10f, 10f) },
    )

    /** Selects s1+s2 (centroids inside a 100px box) and returns the ready VM. */
    private fun selectFirstTwo(vm: CanvasViewModel) {
        vm.onStrokesFinished(listOf(s1, s2, s3))
        vm.selectByLasso(square(100f))
    }

    @Test
    fun `lasso selects only the enclosed strokes`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        assertEquals(2, vm.selection.value?.count)
    }

    @Test
    fun `an empty lasso clears the selection`() {
        val vm = viewModel()
        vm.onStrokesFinished(listOf(s1, s2, s3))
        vm.selectByLasso(square(100f))
        assertNotNull(vm.selection.value)

        vm.selectByLasso(listOf(p(900f, 900f), p(910f, 900f), p(910f, 910f), p(900f, 910f)))
        assertNull(vm.selection.value)
    }

    @Test
    fun `selecting one member of a group selects the whole group`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.groupSelection()
        vm.clearSelection()

        // Lasso only s1's centroid (a 15px box) — selection expands to the whole group.
        vm.selectByLasso(square(15f))
        assertEquals(2, vm.selection.value?.count)
    }

    @Test
    fun `duplicate adds offset copies and selects them, originals kept`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.duplicateSelection()

        assertEquals(5, vm.finishedStrokes.value.size) // 3 originals + 2 duplicates
        assertEquals(2, vm.selection.value?.count) // the duplicates are now selected
    }

    @Test
    fun `delete removes the selected strokes and is undoable`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.deleteSelection()

        assertEquals(listOf(s3), vm.finishedStrokes.value.map { it.stroke })
        assertNull(vm.selection.value)

        vm.undo()
        assertEquals(3, vm.finishedStrokes.value.size)
    }

    @Test
    fun `copy arms the clipboard and keeps the selection`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.copySelection()

        assertTrue(vm.clipboard.value.active)
        assertEquals(2, vm.clipboard.value.count)
        assertNotNull(vm.selection.value) // copy doesn't deselect
    }

    @Test
    fun `cut arms the clipboard and removes the selection`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.cutSelection()

        assertEquals(2, vm.clipboard.value.count)
        assertEquals(listOf(s3), vm.finishedStrokes.value.map { it.stroke })
        assertNull(vm.selection.value)
    }

    @Test
    fun `paste stamps the clipboard, auto-selects it, and stays armed for repeats`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.copySelection()
        vm.clearSelection()

        vm.pasteAt(300f, 300f)
        assertEquals(5, vm.finishedStrokes.value.size) // 3 + 2 pasted
        assertEquals(2, vm.selection.value?.count) // pasted copy is selected
        assertTrue(vm.clipboard.value.active) // clipboard kept for another stamp

        vm.clearSelection()
        vm.pasteAt(400f, 400f)
        assertEquals(7, vm.finishedStrokes.value.size) // repeatable
    }

    @Test
    fun `clear clipboard empties it and deselects, resetting the tool`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        vm.copySelection()

        vm.clearClipboard()
        assertFalse(vm.clipboard.value.active)
        assertNull(vm.selection.value)
    }

    @Test
    fun `group assigns one shared id, ungroup clears it`() {
        val vm = viewModel()
        selectFirstTwo(vm)

        vm.groupSelection()
        val grouped = vm.finishedStrokes.value.filter { it.stroke === s1 || it.stroke === s2 }
        val groupIds = grouped.mapNotNull { it.groupId }.toSet()
        assertEquals(1, groupIds.size)
        assertTrue(vm.selection.value?.grouped == true)

        vm.ungroupSelection()
        assertTrue(vm.finishedStrokes.value.all { it.groupId == null })
        assertFalse(vm.selection.value?.grouped == true)
    }

    @Test
    fun `lock ratio toggles on the selection`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        assertFalse(vm.selection.value?.lockRatio == true)

        vm.setLockRatio(true)
        assertTrue(vm.selection.value?.lockRatio == true)
    }

    @Test
    fun `committing a move bakes the transform and is undoable`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        val before = vm.finishedStrokes.value

        vm.previewTransform(LiveTransform(dx = 30f, dy = 40f))
        vm.commitTransform()

        // Selected strokes were rebuilt (transform applied); the selection survives by id.
        assertEquals(2, vm.selection.value?.count)
        assertEquals(LiveTransform.IDENTITY, vm.selection.value?.transform)

        vm.undo()
        assertEquals(before, vm.finishedStrokes.value)
    }

    @Test
    fun `AI prompt recognizes the selection and sends it to the provider`() = runTest(dispatcher) {
        val provider = FakeProvider("Paris")
        val vm = viewModel(recognizer = FakeRecognizer("what is the capital of France"), provider = provider)
        selectFirstTwo(vm)

        vm.aiPromptSelection()
        advanceUntilIdle()

        assertEquals(listOf("what is the capital of France"), provider.prompts)
        assertEquals("Paris", vm.aiNotes.value.single().text)
    }

    @Test
    fun `pen ink enqueues background extraction but a lasso edit does not`() = runTest(dispatcher) {
        val repository = mockk<NoteRepository>(relaxed = true)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        val enqueued = mutableListOf<String>()
        val vm = viewModel(repository = repository, enqueue = { enqueued += it })
        advanceUntilIdle()

        // Pen ink → one extraction enqueue.
        vm.onStrokesFinished(listOf(s1, s2, s3))
        advanceUntilIdle()
        assertEquals(listOf("page-1"), enqueued)

        // A lasso duplicate changes the page but must NOT re-run the extractor.
        vm.selectByLasso(square(100f))
        vm.duplicateSelection()
        advanceUntilIdle()
        assertEquals(listOf("page-1"), enqueued)
    }

    private class FakeRecognizer(private val text: String) : HandwritingRecognizer {
        override suspend fun recognize(strokes: List<Stroke>): Result<String> = Result.success(text)
        override suspend fun warmUp() {}
    }

    private class FakeProvider(private val responseText: String) : AIProvider {
        val prompts = mutableListOf<String>()
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            prompts += (request.input as AIInput.Text).text
            return Result.success(
                AIResponse(text = responseText, inputTokens = 1, outputTokens = 1, stopReason = "end_turn"),
            )
        }
    }
}
