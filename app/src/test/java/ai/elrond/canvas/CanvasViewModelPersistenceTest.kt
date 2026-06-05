package ai.elrond.canvas

import ai.elrond.data.NoteRepository
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelPersistenceTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<NoteRepository>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CanvasViewModel(
        repository = repository,
        pageId = "page-1",
    )

    @Test
    fun `saved strokes are loaded when the note opens`() = runTest(dispatcher) {
        val saved = listOf(mockk<Stroke>(), mockk<Stroke>())
        coEvery { repository.loadStrokes("page-1") } returns saved

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(saved, viewModel.finishedStrokes.value)
    }

    @Test
    fun `new strokes are auto-saved after the debounce`() = runTest(dispatcher) {
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        val viewModel = viewModel()
        advanceUntilIdle()

        val stroke = mockk<Stroke>()
        viewModel.onStrokesFinished(listOf(stroke))
        advanceUntilIdle()

        coVerify { repository.replaceStrokes("page-1", listOf(stroke)) }
    }

    @Test
    fun `undo back to the loaded state is also persisted`() = runTest(dispatcher) {
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        viewModel.undo()
        advanceUntilIdle()

        coVerify { repository.replaceStrokes("page-1", emptyList()) }
    }

    @Test
    fun `saved AI response notes are restored when the note opens`() = runTest(dispatcher) {
        val note = ai.elrond.ai.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        coEvery { repository.loadAiNotes("page-1") } returns listOf(note)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(note), viewModel.aiNotes.value)
    }

    @Test
    fun `moving an AI note auto-saves the updated notes`() = runTest(dispatcher) {
        val note = ai.elrond.ai.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        coEvery { repository.loadAiNotes("page-1") } returns listOf(note)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.moveAiNote("n1", dx = 5f, dy = 7f)
        advanceUntilIdle()

        coVerify { repository.replaceAiNotes("page-1", listOf(note.copy(x = 5f, y = 7f))) }
    }

    @Test
    fun `unchanged canvas is not re-saved`() = runTest(dispatcher) {
        val saved = listOf(mockk<Stroke>())
        coEvery { repository.loadStrokes("page-1") } returns saved
        coEvery { repository.loadAiNotes("page-1") } returns emptyList()

        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.replaceStrokes(any(), any()) }
        coVerify(exactly = 0) { repository.replaceAiNotes(any(), any()) }
    }
}
