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
    fun `unchanged canvas is not re-saved`() = runTest(dispatcher) {
        val saved = listOf(mockk<Stroke>())
        coEvery { repository.loadStrokes("page-1") } returns saved

        viewModel()
        advanceUntilIdle()

        coVerify(exactly = 0) { repository.replaceStrokes(any(), any()) }
    }
}
