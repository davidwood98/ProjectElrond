package ai.elrond.canvas

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Thumbnail-generation orchestration in [CanvasViewModel]: the dirty flag is set on a finished
 * stroke, the autosave regenerates the thumbnail (via the injected generator seam) and clears the
 * flag, and an unchanged page neither saves nor regenerates. The real render/cache pipeline is
 * covered by [ThumbnailCacheTest] (logic) + the instrumented suite (device rendering).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelThumbnailTest {

    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<NoteRepository>(relaxed = true)
    private val generated = mutableListOf<String>()
    private val generator: suspend (String) -> Unit = { generated.add(it) }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repository.loadStrokes("page-1") } returns emptyList()
        coEvery { repository.loadAiNotes("page-1") } returns emptyList()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = CanvasViewModel(
        repository = repository,
        pageId = "page-1",
        thumbnailGenerator = generator,
        autoSaveDebounceMillis = 10L,
    )

    @Test
    fun `a finished stroke marks the thumbnail dirty`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onStrokesFinished(listOf(mockk<Stroke>()))

        assertTrue(vm.thumbnailDirty)
    }

    @Test
    fun `autosave regenerates the thumbnail and clears the dirty flag`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        advanceUntilIdle()

        assertEquals(listOf("page-1"), generated)
        assertFalse(vm.thumbnailDirty)
    }

    @Test
    fun `an unchanged page neither saves nor generates a thumbnail`() = runTest(dispatcher) {
        coEvery { repository.loadStrokes("page-1") } returns listOf(CanvasStroke("a", mockk()))
        val vm = viewModel()
        advanceUntilIdle()

        assertTrue(generated.isEmpty())
        assertFalse(vm.thumbnailDirty)
    }
}
