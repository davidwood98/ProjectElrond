package ai.elrond.canvas

import ai.elrond.presentation.CanvasViewModel
import ai.elrond.domain.CanvasStroke
import ai.elrond.data.NoteRepository
import androidx.ink.strokes.Stroke
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns flowOf(saved)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(saved, viewModel.finishedStrokes.value)
    }

    @Test
    fun `new strokes are auto-saved after the debounce`() = runTest(dispatcher) {
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
        val viewModel = viewModel()
        advanceUntilIdle()

        val stroke = mockk<Stroke>()
        viewModel.onStrokesFinished(listOf(stroke))
        advanceUntilIdle()

        // Incremental save: a fresh pen stroke appends to the (empty) persisted prefix.
        val previous = slot<List<CanvasStroke>>()
        val current = slot<List<CanvasStroke>>()
        coVerify { repository.updateStrokes(eq("page-1"), capture(previous), capture(current)) }
        assertEquals(emptyList<CanvasStroke>(), previous.captured)
        assertEquals(listOf(stroke), current.captured.map { it.stroke })
    }

    @Test
    fun `undo back to the loaded state is also persisted`() = runTest(dispatcher) {
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()
        viewModel.undo()
        advanceUntilIdle()

        // Undo removed the stroke, so the page emptied — verified via the incremental save diff.
        coVerify { repository.updateStrokes("page-1", any(), emptyList()) }
    }

    @Test
    fun `saved AI response notes are restored when the note opens`() = runTest(dispatcher) {
        val note = ai.elrond.domain.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
        coEvery { repository.loadAiNotes("page-1") } returns listOf(note)

        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(note), viewModel.aiNotes.value)
    }

    @Test
    fun `moving a selected AI note via a committed transform auto-saves it`() = runTest(dispatcher) {
        val note = ai.elrond.domain.AiInkNote(id = "n1", text = "answer", x = 0f, y = 0f, widthPx = 300f)
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
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
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns emptyFlow()
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
    fun `a stroke drawn while the page is still loading is preserved and appended by autosave`() = runTest(dispatcher) {
        val chunk1 = listOf(CanvasStroke("a", mockk()))
        val chunk2 = listOf(CanvasStroke("b", mockk()))
        val gate = Channel<Unit>()
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns flow {
            emit(chunk1)
            gate.receive()
            emit(chunk2)
        }
        val viewModel = viewModel()
        advanceUntilIdle() // chunk1 landed; the load is parked on the gate

        val user = mockk<Stroke>()
        viewModel.onStrokesFinished(listOf(user))
        advanceUntilIdle()
        gate.send(Unit) // release chunk2 — it must slot in AFTER chunk1 and BEFORE the user stroke
        advanceUntilIdle()

        // Loaded ink keeps its order; the mid-load user stroke survives at the end (the old atomic
        // load assigned the list and silently discarded it).
        assertEquals(listOf("a", "b"), viewModel.finishedStrokes.value.dropLast(1).map { it.id })
        assertEquals(user, viewModel.finishedStrokes.value.last().stroke)
        // Mid-load undo snapshots are dropped — undoing to a partially-loaded page would persist it.
        assertEquals(false, viewModel.canUndo.value)

        // The first autosave appends ONLY the user stroke: loaded chunks are already persisted.
        val previous = slot<List<CanvasStroke>>()
        val current = slot<List<CanvasStroke>>()
        coVerify { repository.updateStrokes(eq("page-1"), capture(previous), capture(current)) }
        assertEquals(listOf("a", "b"), previous.captured.map { it.id })
        assertEquals(3, current.captured.size)
    }

    @Test
    fun `unchanged canvas is not re-saved`() = runTest(dispatcher) {
        val saved = listOf(CanvasStroke("a", mockk()))
        every { repository.loadStrokesProgressive(any(), any(), any()) } returns flowOf(saved)
        coEvery { repository.loadAiNotes("page-1") } returns emptyList()

        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.updateStrokes(any(), any(), any()) }
        coVerify(exactly = 0) { repository.replaceAiNotes(any(), any()) }
    }
}
