package ai.elrond.canvas

import ai.elrond.domain.CanvasTool
import ai.elrond.presentation.CanvasViewModel
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

        assertEquals(listOf(first, second), viewModel.finishedStrokes.value.map { it.stroke })
    }

    @Test
    fun `clearPage removes all strokes`() {
        viewModel.onStrokesFinished(listOf(mockk<Stroke>(), mockk<Stroke>()))

        viewModel.clearPage()

        assertTrue(viewModel.finishedStrokes.value.isEmpty())
    }

    @Test
    fun `undo removes last stroke and redo restores it`() {
        val first = mockk<Stroke>()
        val second = mockk<Stroke>()
        viewModel.onStrokesFinished(listOf(first))
        viewModel.onStrokesFinished(listOf(second))

        viewModel.undo()
        assertEquals(listOf(first), viewModel.finishedStrokes.value.map { it.stroke })

        viewModel.redo()
        assertEquals(listOf(first, second), viewModel.finishedStrokes.value.map { it.stroke })
    }

    @Test
    fun `undo and redo flags track history state`() {
        assertFalse(viewModel.canUndo.value)
        assertFalse(viewModel.canRedo.value)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        assertTrue(viewModel.canUndo.value)
        assertFalse(viewModel.canRedo.value)

        viewModel.undo()
        assertFalse(viewModel.canUndo.value)
        assertTrue(viewModel.canRedo.value)
    }

    @Test
    fun `new stroke clears the redo stack`() {
        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))
        viewModel.undo()
        assertTrue(viewModel.canRedo.value)

        viewModel.onStrokesFinished(listOf(mockk<Stroke>()))

        assertFalse(viewModel.canRedo.value)
    }

    @Test
    fun `clearPage is undoable`() {
        val stroke = mockk<Stroke>()
        viewModel.onStrokesFinished(listOf(stroke))

        viewModel.clearPage()
        assertTrue(viewModel.finishedStrokes.value.isEmpty())

        viewModel.undo()
        assertEquals(listOf(stroke), viewModel.finishedStrokes.value.map { it.stroke })
    }

    @Test
    fun `undo with empty history is a no-op`() {
        viewModel.undo()
        viewModel.redo()

        assertTrue(viewModel.finishedStrokes.value.isEmpty())
    }
}
