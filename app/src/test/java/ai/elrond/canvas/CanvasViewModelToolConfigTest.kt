package ai.elrond.canvas

import ai.elrond.domain.BrushSpec
import ai.elrond.domain.CanvasTool
import ai.elrond.domain.HighlighterColor
import ai.elrond.domain.HighlighterWidth
import ai.elrond.domain.InkLineType
import ai.elrond.domain.PenColor
import ai.elrond.domain.PencilLead
import ai.elrond.presentation.CanvasViewModel
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
    fun `pencil spec uses the selected lead colour, defaulting to HB`() {
        val vm = CanvasViewModel()
        vm.selectTool(CanvasTool.PENCIL)

        val spec = vm.currentBrushSpec()

        assertEquals(BrushSpec.FAMILY_PENCIL, spec.familyKey)
        assertEquals(PencilLead.HB.argb, spec.colorArgb) // default lead = the pre-selector colour
        assertEquals(CanvasViewModel.PENCIL_BRUSH_SIZE, spec.size)

        vm.setPencilLead(PencilLead.TWO_B)
        assertEquals(PencilLead.TWO_B.argb, vm.currentBrushSpec().colorArgb)
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
    fun `a non-solid stroke bakes into grouped segments with one undo step`() {
        val parts = listOf(mockk<androidx.ink.strokes.Stroke>(), mockk<androidx.ink.strokes.Stroke>(), mockk<androidx.ink.strokes.Stroke>())
        val vm = CanvasViewModel(
            strokeSegmenter = { _, _ -> parts },
        )
        vm.setPenLineType(InkLineType.DASHED)

        vm.onStrokesFinished(listOf(mockk<androidx.ink.strokes.Stroke>()))

        val strokes = vm.finishedStrokes.value
        assertEquals(3, strokes.size)
        val groupIds = strokes.map { it.groupId }.toSet()
        assertEquals(1, groupIds.size)
        org.junit.Assert.assertNotNull("segments must share a group", groupIds.single())

        vm.undo()
        assertEquals(0, vm.finishedStrokes.value.size)
    }

    @Test
    fun `a solid stroke bypasses the segmenter and stays ungrouped`() {
        var segmenterCalls = 0
        val vm = CanvasViewModel(
            strokeSegmenter = { s, _ -> segmenterCalls++; listOf(s) },
        )

        vm.onStrokesFinished(listOf(mockk<androidx.ink.strokes.Stroke>()))

        assertEquals(0, segmenterCalls)
        assertEquals(1, vm.finishedStrokes.value.size)
        org.junit.Assert.assertNull(vm.finishedStrokes.value.single().groupId)
    }

    @Test
    fun `a pattern that stays one stroke gets no group`() {
        val vm = CanvasViewModel(strokeSegmenter = { s, _ -> listOf(s) })
        vm.setPenLineType(InkLineType.DASHED)

        vm.onStrokesFinished(listOf(mockk<androidx.ink.strokes.Stroke>()))

        org.junit.Assert.assertNull(vm.finishedStrokes.value.single().groupId)
    }

    @Test
    fun `live pattern stroke buffers points and bakes through the finish pipeline`() {
        val built = mockk<androidx.ink.strokes.Stroke>()
        var builtSpec: BrushSpec? = null
        var builtPoints: List<ai.elrond.domain.InkPoint>? = null
        val vm = CanvasViewModel(
            patternStrokeBuilder = { spec, points ->
                builtSpec = spec
                builtPoints = points
                built
            },
            strokeSegmenter = { s, _ -> listOf(s, s) }, // segmentation still applies to the baked stroke
        )
        vm.setPenLineType(InkLineType.DOTTED)

        vm.beginPatternStroke()
        vm.addPatternPoint(1f, 2f, 0L, 0.5f)
        vm.addPatternPoint(3f, 4f, 8L, 0.6f)
        assertEquals(2, vm.livePatternStroke.value?.points?.size)
        assertEquals(InkLineType.DOTTED, vm.livePatternStroke.value?.lineType)

        vm.finishPatternStroke()

        org.junit.Assert.assertNull(vm.livePatternStroke.value)
        assertEquals(BrushSpec.FAMILY_PRESSURE_PEN, builtSpec?.familyKey)
        assertEquals(2, builtPoints?.size)
        assertEquals(2, vm.finishedStrokes.value.size) // baked stroke went through segmentation
    }

    @Test
    fun `hold-to-straighten preview adjusts its endpoint and commits through the finish pipeline`() {
        var builtPoints: List<ai.elrond.domain.InkPoint>? = null
        var builtSpec: BrushSpec? = null
        val vm = CanvasViewModel(
            patternStrokeBuilder = { spec, points ->
                builtSpec = spec
                builtPoints = points
                mockk<androidx.ink.strokes.Stroke>()
            },
        )
        vm.setPenColor(PenColor.RED)
        vm.setPenLineType(InkLineType.DASHED)

        vm.beginStraightLine(0f, 0f, 40f, 0f)
        assertEquals(InkLineType.DASHED, vm.straightLinePreview.value?.lineType)
        assertEquals(PenColor.RED.argb, vm.straightLinePreview.value?.spec?.colorArgb)

        vm.updateStraightLine(100f, 50f)
        assertEquals(100f, vm.straightLinePreview.value?.x2)
        assertEquals(50f, vm.straightLinePreview.value?.y2)

        vm.commitStraightLine()

        org.junit.Assert.assertNull(vm.straightLinePreview.value)
        assertEquals(BrushSpec.FAMILY_PRESSURE_PEN, builtSpec?.familyKey)
        val points = builtPoints!!
        assertEquals(0f, points.first().x)
        assertEquals(100f, points.last().x)
        assertEquals(50f, points.last().y)
        assertEquals(1, vm.finishedStrokes.value.size) // committed via onStrokesFinished
        vm.undo()
        assertEquals(0, vm.finishedStrokes.value.size) // one undo step
    }

    @Test
    fun `straight line snaps to the horizontal within two degrees and breaks out at five`() {
        val vm = CanvasViewModel(patternStrokeBuilder = { _, _ -> mockk() })
        vm.beginStraightLine(0f, 0f, 40f, 0f)

        // ~1.4° above horizontal: snapped flat, endpoint projected onto y = 0.
        vm.updateStraightLine(200f, 5f)
        assertEquals(0f, vm.straightLinePreview.value?.y2)
        assertEquals(200f, vm.straightLinePreview.value?.x2)

        // ~2.9°: inside the 5° hysteresis, still held flat.
        vm.updateStraightLine(200f, 10f)
        assertEquals(0f, vm.straightLinePreview.value?.y2)

        // ~11°: clearly broken out — the endpoint tracks the pen again.
        vm.updateStraightLine(200f, 40f)
        assertEquals(40f, vm.straightLinePreview.value?.y2)
    }

    @Test
    fun `cancelling a straight line leaves no ink`() {
        val vm = CanvasViewModel(patternStrokeBuilder = { _, _ -> mockk() })
        vm.beginStraightLine(0f, 0f, 40f, 0f)

        vm.cancelStraightLine()

        org.junit.Assert.assertNull(vm.straightLinePreview.value)
        assertEquals(0, vm.finishedStrokes.value.size)
    }

    @Test
    fun `cancelling a live pattern stroke leaves no ink`() {
        val vm = CanvasViewModel(patternStrokeBuilder = { _, _ -> mockk() })
        vm.setPenLineType(InkLineType.DASHED)
        vm.beginPatternStroke()
        vm.addPatternPoint(1f, 2f, 0L, 0.5f)

        vm.cancelPatternStroke()

        org.junit.Assert.assertNull(vm.livePatternStroke.value)
        assertEquals(0, vm.finishedStrokes.value.size)
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
            persistPencilLead = { persisted.add(it) },
        )

        vm.setPenColor(PenColor.BLACK)
        vm.setPenLineType(InkLineType.CENTRELINE)
        vm.setHighlighterColor(HighlighterColor.ORANGE)
        vm.setHighlighterWidth(HighlighterWidth.FINE)
        vm.setPencilLineType(InkLineType.DASH_DOT)
        vm.setPencilLead(PencilLead.B)
        advanceUntilIdle()

        assertEquals(
            listOf<Any>(
                PenColor.BLACK,
                InkLineType.CENTRELINE,
                HighlighterColor.ORANGE,
                HighlighterWidth.FINE,
                InkLineType.DASH_DOT,
                PencilLead.B,
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
            pencilLeadFlow = MutableStateFlow(PencilLead.TWO_H),
        )
        advanceUntilIdle()

        assertEquals(PenColor.RED, vm.penColor.value)
        assertEquals(InkLineType.DOTTED, vm.penLineType.value)
        assertEquals(HighlighterColor.GREEN, vm.highlighterColor.value)
        assertEquals(HighlighterWidth.FINE, vm.highlighterWidth.value)
        assertEquals(InkLineType.DASHED, vm.pencilLineType.value)
        assertEquals(PencilLead.TWO_H, vm.pencilLead.value)
    }
}
