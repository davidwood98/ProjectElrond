package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import ai.elrond.data.CalendarEvent
import ai.elrond.data.CalendarRepository
import ai.elrond.data.NoteRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.SuggestionType
import ai.elrond.domain.NotePage
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import kotlinx.coroutines.flow.emptyFlow
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** FA-2 ViewModel behaviour: accept/reject of background suggestions + enqueue-after-save. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelSuggestionTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<NoteRepository>(relaxed = true)
    private val todoRepository = mockk<TodoRepository>(relaxed = true)
    private val calendarRepository = mockk<CalendarRepository>(relaxed = true)
    private val suggestionRepository = mockk<SuggestionRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
        coEvery { repository.getPage("page-1") } returns NotePage(
            id = "page-1", notebookId = "nb", customTitle = "Standup", createdAt = 1L, modifiedAt = 1L,
        )
        every { suggestionRepository.observePending(any()) } returns flowOf(emptyList())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(enqueue: ((String) -> Unit)? = null) = CanvasViewModel(
        repository = repository,
        todoRepository = todoRepository,
        calendarRepository = calendarRepository,
        suggestionRepository = suggestionRepository,
        pageId = "page-1",
        enqueueExtraction = enqueue,
    )

    @Test
    fun `accepting a TODO suggestion adds the task and removes the suggestion`() = runTest(dispatcher) {
        coEvery { suggestionRepository.get("s1") } returns PendingSuggestion(
            id = "s1", pageId = "page-1", type = SuggestionType.TODO, content = "Buy milk",
            x = 0f, y = 0f, dueAtMillis = 123L, priority = 2,
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.acceptSuggestion("s1")
        advanceUntilIdle()

        val slot = slot<List<TodoRepository.ExtractedTask>>()
        coVerify { todoRepository.addExtracted(capture(slot), "page-1", "Standup") }
        assertEquals("Buy milk", slot.captured.single().content)
        assertEquals(123L, slot.captured.single().dueAt)
        // Accepted rows are KEPT (marked handled), not deleted, so they de-dup future saves.
        coVerify { suggestionRepository.markHandled("s1") }
        coVerify(exactly = 0) { suggestionRepository.remove(any()) }
        coVerify(exactly = 0) { calendarRepository.addSuggestion(any(), any()) }
    }

    @Test
    fun `accepting an EVENT suggestion creates a calendar suggestion`() = runTest(dispatcher) {
        coEvery { suggestionRepository.get("s2") } returns PendingSuggestion(
            id = "s2", pageId = "page-1", type = SuggestionType.EVENT, content = "Standup",
            x = 0f, y = 0f, startMillis = 1000L, endMillis = 2000L, location = "HQ",
        )
        val vm = viewModel()
        advanceUntilIdle()

        vm.acceptSuggestion("s2")
        advanceUntilIdle()

        val slot = slot<CalendarEvent>()
        coVerify { calendarRepository.addSuggestion(capture(slot), "page-1") }
        assertEquals("Standup", slot.captured.title)
        assertEquals(1000L, slot.captured.startTime)
        coVerify { suggestionRepository.markHandled("s2") }
    }

    @Test
    fun `rejecting a suggestion marks it rejected (silent under a written Q)`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.rejectSuggestion("s3")
        advanceUntilIdle()

        coVerify { suggestionRepository.reject("s3") }
    }

    @Test
    fun `a successful autosave enqueues background extraction for the page`() = runTest(dispatcher) {
        val enqueued = mutableListOf<String>()
        val vm = viewModel(enqueue = { enqueued += it })
        advanceUntilIdle()

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals(listOf("page-1"), enqueued)
    }
}
