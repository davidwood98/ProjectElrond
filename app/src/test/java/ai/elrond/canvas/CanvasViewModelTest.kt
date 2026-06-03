package ai.elrond.canvas

import androidx.ink.strokes.Stroke
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasViewModelTest {

    private val viewModel = CanvasViewModel()

    @Test
    fun `defaults to pen tool with stylus-only input`() {
        assertEquals(CanvasTool.PEN, viewModel.tool.value)
        assertTrue(viewModel.stylusOnly.value)
        assertTrue(viewModel.finishedStrokes.value.isEmpty())
    }

    @Test
    fun `selectTool switches active tool`() {
        viewModel.selectTool(CanvasTool.ERASER)
        assertEquals(CanvasTool.ERASER, viewModel.tool.value)

        viewModel.selectTool(CanvasTool.PEN)
        assertEquals(CanvasTool.PEN, viewModel.tool.value)
    }

    @Test
    fun `setStylusOnly toggles palm rejection mode`() {
        viewModel.setStylusOnly(false)
        assertFalse(viewModel.stylusOnly.value)
    }

    @Test
    fun `onStrokesFinished appends strokes in order`() {
        val first = mockk<Stroke>()
        val second = mockk<Stroke>()

        viewModel.onStrokesFinished(listOf(first))
        viewModel.onStrokesFinished(listOf(second))

        assertEquals(listOf(first, second), viewModel.finishedStrokes.value)
    }

    @Test
    fun `clearPage removes all strokes`() {
        viewModel.onStrokesFinished(listOf(mockk<Stroke>(), mockk<Stroke>()))

        viewModel.clearPage()

        assertTrue(viewModel.finishedStrokes.value.isEmpty())
    }
}
