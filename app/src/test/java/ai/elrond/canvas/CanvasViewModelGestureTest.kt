package ai.elrond.canvas

import ai.elrond.domain.CanvasTool
import ai.elrond.domain.FingerGesture
import ai.elrond.domain.FingerGestureAction
import ai.elrond.presentation.CanvasViewModel
import androidx.ink.strokes.Stroke
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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

/** FA-19: [CanvasViewModel.onFingerGesture] dispatches the user-bound action for each gesture. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelGestureTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `two-finger tap runs Undo by default`() {
        val vm = CanvasViewModel()
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        assertEquals(2, vm.finishedStrokes.value.size)

        vm.onFingerGesture(FingerGesture.TwoFingerTap) // default UNDO

        assertEquals(1, vm.finishedStrokes.value.size)
    }

    @Test
    fun `three-finger tap runs Redo by default`() {
        val vm = CanvasViewModel()
        vm.onStrokesFinished(listOf(mockk<Stroke>()))
        vm.undo()
        assertTrue(vm.finishedStrokes.value.isEmpty())

        vm.onFingerGesture(FingerGesture.ThreeFingerTap) // default REDO

        assertEquals(1, vm.finishedStrokes.value.size)
    }

    @Test
    fun `two-finger double tap swaps to the previous tool by default`() {
        val vm = CanvasViewModel()
        vm.selectTool(CanvasTool.ERASER) // previous = PEN
        assertEquals(CanvasTool.ERASER, vm.tool.value)

        vm.onFingerGesture(FingerGesture.TwoFingerDoubleTap) // default LAST_TOOL_SWAP

        assertEquals(CanvasTool.PEN, vm.tool.value)
    }

    @Test
    fun `three-finger double tap is unbound by default and does nothing`() {
        val vm = CanvasViewModel()
        vm.onStrokesFinished(listOf(mockk<Stroke>()))

        vm.onFingerGesture(FingerGesture.ThreeFingerDoubleTap) // default NONE

        assertEquals(1, vm.finishedStrokes.value.size)
        assertEquals(CanvasTool.PEN, vm.tool.value)
    }

    @Test
    fun `a select-hand binding enables finger draw`() = runTest {
        val vm = CanvasViewModel(
            twoFingerTapActionFlow = MutableStateFlow(FingerGestureAction.SELECT_HAND),
        )
        advanceUntilIdle() // let the action flow collect into the VM
        assertTrue(vm.stylusOnly.value)

        vm.onFingerGesture(FingerGesture.TwoFingerTap)

        assertFalse(vm.stylusOnly.value)
    }

    @Test
    fun `a select-tool binding switches tools`() = runTest {
        val vm = CanvasViewModel(
            threeFingerTapActionFlow = MutableStateFlow(FingerGestureAction.SELECT_LASSO),
        )
        advanceUntilIdle()

        vm.onFingerGesture(FingerGesture.ThreeFingerTap)

        assertEquals(CanvasTool.LASSO, vm.tool.value)
    }

    @Test
    fun `isDoubleTapBound reflects the default bindings`() {
        val vm = CanvasViewModel()
        assertTrue(vm.isDoubleTapBound(2)) // 2x2 = LAST_TOOL_SWAP
        assertFalse(vm.isDoubleTapBound(3)) // 2x3 = NONE
        assertFalse(vm.isDoubleTapBound(1)) // not a gesture count
    }
}
