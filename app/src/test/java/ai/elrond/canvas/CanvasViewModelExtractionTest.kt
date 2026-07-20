package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import ai.elrond.domain.NotePosition
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import ai.elrond.aibackend.ExtractedTask
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.data.HandwritingRecognizer
import ai.elrond.data.NoteRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.domain.NotePage
import ai.elrond.domain.SelectionBounds
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelExtractionTest {

    private val dispatcher = StandardTestDispatcher()
    private val noteRepository = mockk<NoteRepository>(relaxed = true)
    private val todoRepository = mockk<TodoRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { noteRepository.loadStrokes(any(), any()) } returns emptyList()
        coEvery { noteRepository.getPage("page-1") } returns NotePage(
            id = "page-1",
            notebookId = "nb-1",
            customTitle = "Standup",
            createdAt = 1L,
            modifiedAt = 1L,
        )
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeExtractor(private val tasks: List<ExtractedTask>) : TaskExtractor {
        override suspend fun extract(
            noteContent: String,
            referenceDate: String?,
            existingTasks: List<String>,
        ): Result<List<ExtractedTask>> = Result.success(tasks)
    }

    private fun viewModel(
        tasks: List<ExtractedTask>,
        suggestionRepository: SuggestionRepository? = null,
        // Recognizer text drives both the `/Q` command detection AND lasso selection recognition.
        recognizerText: String = "buy milk /Q",
    ) = CanvasViewModel(
        recognizer = object : HandwritingRecognizer {
            override suspend fun recognize(strokes: List<Stroke>) = Result.success(recognizerText)
        },
        aiProvider = object : AIProvider {
            override suspend fun generate(request: AIRequest) = Result.success(
                AIResponse((request.input as AIInput.Text).text, 1, 1, "end_turn"),
            )
        },
        lineSplitter = { listOf(it) },
        notePlacer = { NotePosition(0f, 0f) },
        repository = noteRepository,
        taskExtractor = FakeExtractor(tasks),
        todoRepository = todoRepository,
        suggestionRepository = suggestionRepository,
        pageId = "page-1",
        // Geometry-free ink seams so selectByLasso/aiPromptSelection work with mockk strokes.
        centroidOf = { GestureTriggerDetector.Point(10f, 10f) },
        strokeTransformer = { stroke, _ -> stroke },
        strokeBoundsOf = { SelectionBounds(0f, 0f, 10f, 10f) },
        lassoSnapBackThresholdFlow = MutableStateFlow(0f),
        lassoSnapBackEnabledFlow = MutableStateFlow(false),
    )

    @Test
    fun `extracted tasks raise a confirmation linked to the source note`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Buy milk", priority = 3, dueDateIso = "2026-06-10")))

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val pending = vm.pendingExtraction.value!!
        assertEquals(listOf("Buy milk"), pending.tasks)
        assertEquals("Standup", pending.sourcePageTitle)
        coVerify(exactly = 0) { todoRepository.addExtracted(any(), any(), any()) } // not yet saved
    }

    @Test
    fun `confirming saves the tasks against the source page`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Buy milk", priority = 2)))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val tasksSlot = slot<List<TodoRepository.ExtractedTask>>()
        coEvery { todoRepository.addExtracted(capture(tasksSlot), "page-1", "Standup") } returns emptyList()

        vm.confirmExtraction()
        advanceUntilIdle()

        assertNull(vm.pendingExtraction.value)
        assertEquals("Buy milk", tasksSlot.captured.single().content)
        assertEquals(ai.elrond.domain.TodoPriority.MEDIUM, tasksSlot.captured.single().priority)
    }

    @Test
    fun `confirming a subset adds only the toggled-on tasks`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Task A"), ExtractedTask("Task B")))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val tasksSlot = slot<List<TodoRepository.ExtractedTask>>()
        coEvery { todoRepository.addExtracted(capture(tasksSlot), any(), any()) } returns emptyList()

        vm.confirmExtraction(selectedIndices = setOf(1)) // keep only the second
        advanceUntilIdle()

        assertEquals(listOf("Task B"), tasksSlot.captured.map { it.content })
    }

    @Test
    fun `no answer is written to the canvas when tasks are detected`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Buy milk")))

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        // It's a to-do, not a question — the /Q answer is suppressed.
        assertTrue(vm.aiNotes.value.isEmpty())
        assertEquals(listOf("Buy milk"), vm.pendingExtraction.value?.tasks)
    }

    @Test
    fun `already-existing tasks raise a self-clearing notification instead of an answer`() = runTest(dispatcher) {
        coEvery { todoRepository.existingContents() } returns setOf("buy milk")
        val vm = viewModel(listOf(ExtractedTask("Buy milk")))

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        // Reach the detection + extraction, but stop before the transient message auto-clears.
        advanceTimeBy(CanvasViewModel.TRIGGER_DEBOUNCE_MILLIS + 1)
        runCurrent()

        assertNull(vm.pendingExtraction.value) // nothing new to add
        assertTrue(vm.aiNotes.value.isEmpty()) // no /Q answer for an already-captured item
        assertEquals(CanvasViewModel.ALREADY_EXISTS_MESSAGE, vm.transientMessage.value)
    }

    @Test
    fun `manual extraction does not mark offered items handled — an offer is not a decision`() =
        runTest(dispatcher) {
            val suggestionRepository = mockk<SuggestionRepository>(relaxed = true)
            coEvery { todoRepository.existingContents() } returns emptySet()
            val vm = viewModel(listOf(ExtractedTask("Buy milk")), suggestionRepository)

            vm.onStrokesFinished(listOf(mockk<Stroke>()))
            advanceUntilIdle()

            // Merely offering must NOT record the item as handled/dismissed — otherwise the next
            // trigger would wrongly treat it as "already on your to-do list".
            coVerify(exactly = 0) { suggestionRepository.recordHandled(any()) }
        }

    @Test
    fun `an ignored (not-now) background suggestion is re-offered by a written Q and its popup claimed`() =
        runTest(dispatcher) {
            val suggestionRepository = mockk<SuggestionRepository>(relaxed = true)
            // Ignored, not rejected → rejectedContents empty → a written /Q re-offers it.
            coEvery { todoRepository.existingContents() } returns emptySet()
            coEvery { suggestionRepository.rejectedContents("page-1") } returns emptySet()
            val vm = viewModel(listOf(ExtractedTask("Buy milk")), suggestionRepository)

            vm.onStrokesFinished(listOf(mockk<Stroke>()))
            advanceUntilIdle()

            assertEquals(listOf("Buy milk"), vm.pendingExtraction.value?.tasks)
            assertNull(vm.transientMessage.value) // no false "already on your to-do list"
            // The stale on-canvas popup for the same item is removed so it can't be added twice.
            coVerify { suggestionRepository.claimPendingTodos("page-1", match { it.contains("buy milk") }) }
        }

    @Test
    fun `a rejected line stays silent under a written Q — no sheet, no toast, normal answer`() =
        runTest(dispatcher) {
            val suggestionRepository = mockk<SuggestionRepository>(relaxed = true)
            // Explicitly rejected and not on the to-do list → /Q stays silent (falls through to answer).
            coEvery { todoRepository.existingContents() } returns emptySet()
            coEvery { suggestionRepository.rejectedContents("page-1") } returns setOf("buy milk")
            val vm = viewModel(listOf(ExtractedTask("Buy milk")), suggestionRepository)

            vm.onStrokesFinished(listOf(mockk<Stroke>()))
            advanceUntilIdle()

            assertNull(vm.pendingExtraction.value) // no re-offer sheet
            assertNull(vm.transientMessage.value) // silent — NOT "Already on your to-do list"
            assertTrue(vm.aiNotes.value.isNotEmpty()) // fell through to a normal /Q answer
            coVerify(exactly = 0) { suggestionRepository.claimPendingTodos(any(), any()) }
        }

    @Test
    fun `a lasso forces a re-offer even for a rejected line`() =
        runTest(dispatcher) {
            val suggestionRepository = mockk<SuggestionRepository>(relaxed = true)
            // Rejected under /Q, but a lasso is a deliberate override → it re-offers regardless.
            coEvery { todoRepository.existingContents() } returns emptySet()
            coEvery { suggestionRepository.rejectedContents("page-1") } returns setOf("buy milk")
            // recognizerText has no "/Q", so onStrokesFinished won't auto-trigger; the lasso drives it.
            val vm = viewModel(listOf(ExtractedTask("Buy milk")), suggestionRepository, recognizerText = "buy milk")

            vm.onStrokesFinished(listOf(mockk<Stroke>()))
            vm.selectByLasso(listOf(p(0f, 0f), p(100f, 0f), p(100f, 100f), p(0f, 100f)))
            vm.aiPromptSelection()
            advanceUntilIdle()

            assertEquals(listOf("Buy milk"), vm.pendingExtraction.value?.tasks) // re-offered despite reject
            // rejectedContents must NOT be consulted for a lasso.
            coVerify(exactly = 0) { suggestionRepository.rejectedContents(any()) }
        }

    private fun p(x: Float, y: Float) = GestureTriggerDetector.Point(x, y)

    @Test
    fun `dismissing does not persist anything`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Buy milk")))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        vm.dismissExtraction()
        advanceUntilIdle()

        assertNull(vm.pendingExtraction.value)
        coVerify(exactly = 0) { todoRepository.addExtracted(any(), any(), any()) }
    }

    @Test
    fun `no tasks means a normal answer and no confirmation`() = runTest(dispatcher) {
        val vm = viewModel(emptyList())

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertNull(vm.pendingExtraction.value)
        assertEquals(1, vm.aiNotes.value.size)
    }

    @Test
    fun `ISO due date is parsed to a timestamp`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Submit form", dueDateIso = "2026-06-10")))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        val tasksSlot = slot<List<TodoRepository.ExtractedTask>>()
        coEvery { todoRepository.addExtracted(capture(tasksSlot), any(), any()) } returns emptyList()

        vm.confirmExtraction()
        advanceUntilIdle()

        assertTrue((tasksSlot.captured.single().dueAt ?: 0L) > 0L)
    }
}
