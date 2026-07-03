package ai.elrond.canvas

import ai.elrond.domain.BrushSpec
import ai.elrond.domain.CanvasTool
import ai.elrond.domain.HighlighterColor
import ai.elrond.domain.HighlighterWidth
import ai.elrond.domain.InkLineType
import ai.elrond.domain.PenColor
import ai.elrond.presentation.CanvasViewModel
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
import org.junit.Before
import org.junit.Test

/** FA-23: per-tool brush derivation ([CanvasViewModel.currentBrushSpec]) + config state/persistence. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanvasViewModelToolConfigTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `pen spec uses the configured colour and the pen defaults`() {
        val vm = CanvasViewModel()
        vm.setPenColor(PenColor.RED)

        val spec = vm.currentBrushSpec()

        assertEquals(BrushSpec.FAMILY_PRESSURE_PEN, spec.familyKey)
        assertEquals(PenColor.RED.argb, spec.colorArgb)
        assertEquals(CanvasViewModel.DEFAULT_BRUSH_SIZE, spec.size)
        assertEquals(CanvasViewModel.BRUSH_EPSILON, spec.epsilon)
    }

    @Test
    fun `highlighter spec follows its colour and width config`() {
        val vm = CanvasViewModel()
        vm.selectTool(CanvasTool.HIGHLIGHTER)
        vm.setHighlighterColor(HighlighterColor.PINK)
        vm.setHighlighterWidth(HighlighterWidth.THICK)

        val spec = vm.currentBrushSpec()

        assertEquals(BrushSpec.FAMILY_HIGHLIGHTER, spec.familyKey)
        assertEquals(HighlighterColor.PINK.argb, spec.colorArgb)
        assertEquals(HighlighterWidth.THICK.size, spec.size)
        assertEquals(CanvasViewModel.HIGHLIGHTER_EPSILON, spec.epsilon)
    }

    @Test
    fun `pencil spec uses the graphite constants`() {
        val vm = CanvasViewModel()
        vm.selectTool(CanvasTool.PENCIL)

        val spec = vm.currentBrushSpec()

        assertEquals(BrushSpec.FAMILY_PENCIL, spec.familyKey)
        assertEquals(CanvasViewModel.PENCIL_COLOR, spec.colorArgb)
        assertEquals(CanvasViewModel.PENCIL_BRUSH_SIZE, spec.size)
    }

    @Test
    fun `eraser and lasso fall back to the pen spec`() {
        val vm = CanvasViewModel()
        vm.setPenColor(PenColor.BLACK)
        vm.selectTool(CanvasTool.LASSO)
        assertEquals(PenColor.BLACK.argb, vm.currentBrushSpec().colorArgb)
        vm.selectTool(CanvasTool.ERASER)
        assertEquals(BrushSpec.FAMILY_PRESSURE_PEN, vm.currentBrushSpec().familyKey)
    }

    @Test
    fun `line type is per tool and solid elsewhere`() {
        val vm = CanvasViewModel()
        vm.setPenLineType(InkLineType.DASHED)
        vm.setPencilLineType(InkLineType.DOTTED)

        assertEquals(InkLineType.DASHED, vm.currentLineType())
        vm.selectTool(CanvasTool.PENCIL)
        assertEquals(InkLineType.DOTTED, vm.currentLineType())
        vm.selectTool(CanvasTool.HIGHLIGHTER)
        assertEquals(InkLineType.SOLID, vm.currentLineType())
    }

    @Test
    fun `selecting the new tools records the previous tool for last-tool swap`() {
        val vm = CanvasViewModel()
        vm.selectTool(CanvasTool.HIGHLIGHTER)
        vm.onFingerGesture(ai.elrond.domain.FingerGesture.TwoFingerDoubleTap) // default LAST_TOOL_SWAP
        assertEquals(CanvasTool.PEN, vm.tool.value)
    }

    @Test
    fun `setters write through to persistence`() = runTest {
        val persisted = mutableListOf<Any>()
        val vm = CanvasViewModel(
            persistPenColor = { persisted.add(it) },
            persistPenLineType = { persisted.add(it) },
            persistHighlighterColor = { persisted.add(it) },
            persistHighlighterWidth = { persisted.add(it) },
            persistPencilLineType = { persisted.add(it) },
        )

        vm.setPenColor(PenColor.BLACK)
        vm.setPenLineType(InkLineType.CENTRELINE)
        vm.setHighlighterColor(HighlighterColor.ORANGE)
        vm.setHighlighterWidth(HighlighterWidth.FINE)
        vm.setPencilLineType(InkLineType.DASH_DOT)
        advanceUntilIdle()

        assertEquals(
            listOf<Any>(
                PenColor.BLACK,
                InkLineType.CENTRELINE,
                HighlighterColor.ORANGE,
                HighlighterWidth.FINE,
                InkLineType.DASH_DOT,
            ),
            persisted,
        )
    }

    @Test
    fun `settings flows seed the config state`() = runTest {
        val vm = CanvasViewModel(
            penColorFlow = MutableStateFlow(PenColor.RED),
            penLineTypeFlow = MutableStateFlow(InkLineType.DOTTED),
            highlighterColorFlow = MutableStateFlow(HighlighterColor.GREEN),
            highlighterWidthFlow = MutableStateFlow(HighlighterWidth.FINE),
            pencilLineTypeFlow = MutableStateFlow(InkLineType.DASHED),
        )
        advanceUntilIdle()

        assertEquals(PenColor.RED, vm.penColor.value)
        assertEquals(InkLineType.DOTTED, vm.penLineType.value)
        assertEquals(HighlighterColor.GREEN, vm.highlighterColor.value)
        assertEquals(HighlighterWidth.FINE, vm.highlighterWidth.value)
        assertEquals(InkLineType.DASHED, vm.pencilLineType.value)
    }
}
