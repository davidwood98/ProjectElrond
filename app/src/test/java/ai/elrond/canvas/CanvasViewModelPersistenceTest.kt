package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import ai.elrond.domain.CanvasStroke
import ai.elrond.data.NoteRepository
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
        val saved = listOf(CanvasStroke("a", mockk()), CanvasStroke("b", mockk()))
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

        val slot = slot<List<CanvasStroke>>()
        coVerify { repository.replaceStrokes(eq("page-1"), capture(slot)) }
        assertEquals(listOf(stroke), slot.captured.map { it.stroke })
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
        val note = ai.elrond.domain.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        coEvery { repository.loadAiNotes("page-1") } returns listOf(note)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(note), viewModel.aiNotes.value)
    }

    @Test
    fun `moving a selected AI note via a committed transform auto-saves it`() = runTest(dispatcher) {
        val note = ai.elrond.domain.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        coEvery { repository.loadAiNotes("page-1") } returns listOf(note)
        val viewModel = viewModel()
        advanceUntilIdle()

        // FA-21: AI-box moves flow through the shared selection transform (no canvas size → no snap-back).
        viewModel.selectAiNote("n1")
        viewModel.previewTransform(ai.elrond.domain.LiveTransform(dx = 5f, dy = 7f))
        viewModel.commitTransform()
        advanceUntilIdle()

        coVerify { repository.replaceAiNotes("page-1", listOf(note.copy(x = 5f, y = 7f))) }
    }

    @Test
    fun `reflowing an AI note auto-saves the new width`() = runTest(dispatcher) {
        val note = ai.elrond.domain.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        coEvery { repository.loadAiNotes("page-1") } returns listOf(note)
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.reflowAiNoteWidth("n1", x = 0f, widthPx = 350f)
        advanceUntilIdle()

        val slot = slot<List<ai.elrond.domain.AiInkNote>>()
        coVerify { repository.replaceAiNotes(eq("page-1"), capture(slot)) }
        assertEquals(350f, slot.captured.single().widthPx)
        // Reflow clears the explicit height so the text wraps to content (FA-21).
        assertEquals(null, slot.captured.single().heightPx)
    }

    @Test
    fun `unchanged canvas is not re-saved`() = runTest(dispatcher) {
        val saved = listOf(CanvasStroke("a", mockk()))
        coEvery { repository.loadStrokes("page-1") } returns saved
        coEvery { repository.loadAiNotes("page-1") } returns emptyList()

        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.replaceStrokes(any(), any()) }
        coVerify(exactly = 0) { repository.replaceAiNotes(any(), any()) }
    }
}
