package ai.elrond.canvas

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds canvas drawing state: finished (dry) strokes, the active tool, and brush settings.
 *
 * Wet (in-progress) ink is owned by [androidx.ink.authoring.InProgressStrokesView] for
 * low-latency front-buffer rendering; strokes are handed to this ViewModel once finished.
 */
class CanvasViewModel : ViewModel() {

    private val _finishedStrokes = MutableStateFlow<List<Stroke>>(emptyList())
    val finishedStrokes: StateFlow<List<Stroke>> = _finishedStrokes.asStateFlow()

    private val _tool = MutableStateFlow(CanvasTool.PEN)
    val tool: StateFlow<CanvasTool> = _tool.asStateFlow()

    /** Palm rejection: when true (default), finger touches never draw ink. */
    private val _stylusOnly = MutableStateFlow(true)
    val stylusOnly: StateFlow<Boolean> = _stylusOnly.asStateFlow()

    /** Pressure-sensitive pen brush for user ink. Lazy so JVM unit tests never touch ink natives. */
    val penBrush: Brush by lazy {
        Brush.createWithColorIntArgb(
            family = StockBrushes.pressurePenLatest,
            colorIntArgb = USER_INK_COLOR,
            size = DEFAULT_BRUSH_SIZE,
            epsilon = BRUSH_EPSILON,
        )
    }

    fun selectTool(tool: CanvasTool) {
        _tool.value = tool
    }

    fun setStylusOnly(enabled: Boolean) {
        _stylusOnly.value = enabled
    }

    /** Called by the canvas when wet strokes complete and become dry strokes. */
    fun onStrokesFinished(strokes: Collection<Stroke>) {
        _finishedStrokes.update { it + strokes }
    }

    /** Erase any stroke whose geometry intersects the eraser position. */
    fun eraseAt(x: Float, y: Float, radius: Float = ERASER_RADIUS) {
        val eraserBox = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(x, y),
            radius * 2,
            radius * 2,
        )
        _finishedStrokes.update { strokes ->
            strokes.filterNot { stroke ->
                stroke.shape.computeCoverageIsGreaterThan(eraserBox, 0f)
            }
        }
    }

    fun clearPage() {
        _finishedStrokes.value = emptyList()
    }

    companion object {
        /** Dark ink for user strokes; AI response ink will use a distinct colour. */
        const val USER_INK_COLOR: Int = 0xFF1A237E.toInt()
        const val DEFAULT_BRUSH_SIZE: Float = 4f
        const val BRUSH_EPSILON: Float = 0.1f
        const val ERASER_RADIUS: Float = 16f
    }
}
