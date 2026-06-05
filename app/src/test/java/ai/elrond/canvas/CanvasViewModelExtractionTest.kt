package ai.elrond.canvas

import ai.elrond.ai.NotePosition
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AIResponse
import ai.elrond.aibackend.ExtractedTask
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.ai.HandwritingRecognizer
import ai.elrond.data.NoteRepository
import ai.elrond.data.TodoRepository
import ai.elrond.notes.NotePage
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
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
        coEvery { noteRepository.loadStrokes(any()) } returns emptyList()
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
        override suspend fun extract(noteContent: String): Result<List<ExtractedTask>> =
            Result.success(tasks)
    }

    private fun viewModel(tasks: List<ExtractedTask>) = CanvasViewModel(
        recognizer = object : HandwritingRecognizer {
            override suspend fun recognize(strokes: List<Stroke>) = Result.success("buy milk /Q")
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
        pageId = "page-1",
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
        assertEquals(ai.elrond.todo.TodoPriority.MEDIUM, tasksSlot.captured.single().priority)
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
    fun `confirming discards the Q echo note from the canvas`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Buy milk")))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        assertEquals(1, vm.aiNotes.value.size) // the /Q answer echo is on the canvas

        coEvery { todoRepository.addExtracted(any(), any(), any()) } returns emptyList()
        vm.confirmExtraction()
        advanceUntilIdle()

        assertTrue(vm.aiNotes.value.isEmpty()) // echo removed once tasks are saved
    }

    @Test
    fun `dismissing keeps the echo note and persists nothing`() = runTest(dispatcher) {
        val vm = viewModel(listOf(ExtractedTask("Buy milk")))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        vm.dismissExtraction()
        advanceUntilIdle()

        assertEquals(1, vm.aiNotes.value.size) // echo stays
        coVerify(exactly = 0) { todoRepository.addExtracted(any(), any(), any()) }
    }

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
    fun `no tasks means no confirmation`() = runTest(dispatcher) {
        val vm = viewModel(emptyList())

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertNull(vm.pendingExtraction.value)
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
