package ai.elrond.canvas

import ai.elrond.domain.SelectionBounds
import ai.elrond.domain.LiveTransform
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.data.HandwritingRecognizer
import ai.elrond.domain.NotePosition
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import ai.elrond.data.NoteRepository
import ai.elrond.data.SettingsRepository
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
        canvasWidth: Float = 0f,
        canvasHeight: Float = 0f,
        snapBackThreshold: Float = SettingsRepository.DEFAULT_LASSO_SNAP_BACK_THRESHOLD,
        snapBackEnabled: Boolean = true,
        // Invoked with every transform baked by commitTransform (and clone) — lets a test count
        // bakes (a real move-commit vs a snap-back, which bakes nothing) AND assert the exact
        // transform applied, since the fake transformer is otherwise a geometry-free no-op.
        onTransform: (LiveTransform) -> Unit = {},
    ): CanvasViewModel {
        val vm = CanvasViewModel(
            recognizer = recognizer,
            aiProvider = provider,
            lineSplitter = { listOf(it) },
            notePlacer = { NotePosition(0f, 0f) },
            repository = repository,
            pageId = if (repository != null) "page-1" else null,
            enqueueExtraction = enqueue,
            centroidOf = { centroids[it] ?: p(0f, 0f) },
            // Geometry-free fakes: keep the stroke object, report a fixed box (ink natives untouched).
            strokeTransformer = { stroke, t -> onTransform(t); stroke },
            strokeBoundsOf = { SelectionBounds(0f, 0f, 10f, 10f) },
            lassoSnapBackThresholdFlow = MutableStateFlow(snapBackThreshold),
            lassoSnapBackEnabledFlow = MutableStateFlow(snapBackEnabled),
        )
        if (canvasWidth > 0f || canvasHeight > 0f) vm.setCanvasSize(canvasWidth, canvasHeight)
        return vm
    }

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
    fun `lock ratio is on by default and toggles on the selection`() {
        val vm = viewModel()
        selectFirstTwo(vm)
        // FA-21: aspect lock defaults ON.
        assertTrue(vm.selection.value?.lockRatio == true)

        vm.setLockRatio(false)
        assertFalse(vm.selection.value?.lockRatio == true)

        vm.setLockRatio(true)
        assertTrue(vm.selection.value?.lockRatio == true)
    }

    @Test
    fun `a move previews on the box, then bakes the final transform once per stroke on commit`() =
        runTest(dispatcher) {
            val baked = mutableListOf<LiveTransform>()
            val vm = viewModel(canvasWidth = 1000f, canvasHeight = 1000f, onTransform = { baked += it })
            advanceUntilIdle()
            selectFirstTwo(vm)
            val before = vm.finishedStrokes.value

            // Preview: the box (displayBounds) tracks the live transform; nothing is baked yet.
            vm.previewTransform(LiveTransform(dx = 10f, dy = 20f))
            vm.previewTransform(LiveTransform(dx = 30f, dy = 40f))
            assertTrue("preview must not bake any stroke", baked.isEmpty())
            val shown = vm.selection.value!!.displayBounds // baseline bounds (0,0,10,10) + (30,40)
            assertEquals(30f, shown.left, 1e-3f)
            assertEquals(40f, shown.top, 1e-3f)
            assertEquals(40f, shown.right, 1e-3f)

            // Commit: bakes the FINAL transform exactly once per selected stroke (2), back to identity.
            vm.commitTransform()
            assertEquals(
                listOf(LiveTransform(dx = 30f, dy = 40f), LiveTransform(dx = 30f, dy = 40f)),
                baked,
            )
            assertEquals(2, vm.selection.value?.count) // selection survives the bake (tracked by id)
            assertEquals(LiveTransform.IDENTITY, vm.selection.value?.transform)

            vm.undo()
            assertEquals(before, vm.finishedStrokes.value)
        }

    @Test
    fun `cancelling a move bakes nothing and resets the transform`() = runTest(dispatcher) {
        val baked = mutableListOf<LiveTransform>()
        val vm = viewModel(canvasWidth = 1000f, canvasHeight = 1000f, onTransform = { baked += it })
        advanceUntilIdle()
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 200f, dy = 200f)) // well beyond the snap threshold
        vm.cancelTransform()

        assertTrue(baked.isEmpty())
        assertEquals(LiveTransform.IDENTITY, vm.selection.value?.transform)
        assertEquals(2, vm.selection.value?.count)
    }

    // --- FA-10 snap-back ---

    @Test
    fun `a small move released near origin snaps back without baking`() = runTest(dispatcher) {
        var transforms = 0
        val vm = viewModel(
            canvasWidth = 1000f, canvasHeight = 1000f,
            snapBackThreshold = 0.025f, snapBackEnabled = true,
            onTransform = { transforms++ },
        )
        advanceUntilIdle() // apply the snap-back settings flows
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 10f, dy = 10f)) // ~0.0141 < 0.025 → snap back
        vm.commitTransform()

        assertEquals(LiveTransform.IDENTITY, vm.selection.value?.transform)
        assertEquals(0, transforms) // nothing was baked
        assertEquals(2, vm.selection.value?.count) // selection survives
    }

    @Test
    fun `a move beyond the threshold commits the transform`() = runTest(dispatcher) {
        var transforms = 0
        val vm = viewModel(
            canvasWidth = 1000f, canvasHeight = 1000f,
            snapBackThreshold = 0.025f, onTransform = { transforms++ },
        )
        advanceUntilIdle()
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 100f, dy = 100f)) // ~0.141 > 0.025 → commit
        vm.commitTransform()

        assertEquals(2, transforms) // both selected strokes baked
        assertEquals(LiveTransform.IDENTITY, vm.selection.value?.transform)
    }

    @Test
    fun `a move exactly at the threshold commits (strict boundary)`() = runTest(dispatcher) {
        var transforms = 0
        // 100x100 canvas, 10% threshold; a 10px move = 0.1 exactly == threshold → no snap.
        val vm = viewModel(
            canvasWidth = 100f, canvasHeight = 100f,
            snapBackThreshold = 0.1f, onTransform = { transforms++ },
        )
        advanceUntilIdle()
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 10f, dy = 0f))
        vm.commitTransform()

        assertEquals(2, transforms) // committed, not snapped
    }

    @Test
    fun `a zero threshold disables snap-back so even a tiny move commits`() = runTest(dispatcher) {
        var transforms = 0
        val vm = viewModel(
            canvasWidth = 1000f, canvasHeight = 1000f,
            snapBackThreshold = 0f, onTransform = { transforms++ },
        )
        advanceUntilIdle()
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 2f, dy = 2f))
        vm.commitTransform()

        assertEquals(2, transforms)
    }

    @Test
    fun `snap-back turned off commits a small move`() = runTest(dispatcher) {
        var transforms = 0
        val vm = viewModel(
            canvasWidth = 1000f, canvasHeight = 1000f,
            snapBackThreshold = 0.025f, snapBackEnabled = false,
            onTransform = { transforms++ },
        )
        advanceUntilIdle()
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 5f, dy = 5f))
        vm.commitTransform()

        assertEquals(2, transforms)
    }

    @Test
    fun `with no canvas size reported a small move still commits`() = runTest(dispatcher) {
        // No setCanvasSize → dimensions unknown → snap-back inert (mirrors maxWidthAt gating).
        var transforms = 0
        val vm = viewModel(snapBackThreshold = 0.025f, onTransform = { transforms++ })
        advanceUntilIdle()
        selectFirstTwo(vm)

        vm.previewTransform(LiveTransform(dx = 1f, dy = 1f))
        vm.commitTransform()

        assertEquals(2, transforms)
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
