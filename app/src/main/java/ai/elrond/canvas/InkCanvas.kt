package ai.elrond.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Handwriting canvas with two ink layers:
 *  - Wet ink: [InProgressStrokesView] renders in-progress strokes with front-buffered,
 *    low-latency rendering (120Hz-capable) plus motion prediction.
 *  - Dry ink: finished strokes from [CanvasViewModel] drawn via [CanvasStrokeRenderer].
 *
 * Input handling supports S Pen pressure/tilt (via the pressure-pen stock brush), palm
 * rejection (finger input ignored while stylus-only mode is on), the hardware stylus
 * eraser ([MotionEvent.TOOL_TYPE_ERASER]), and a drawn eraser tool.
 */
@Composable
fun InkCanvas(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    val finishedStrokes by viewModel.finishedStrokes.collectAsStateWithLifecycle()
    val canvasStrokeRenderer = remember { CanvasStrokeRenderer.create() }
    val identityTransform = remember { Matrix() }

    Box(modifier = modifier) {
        // Dry layer: finished strokes.
        // Read the list in composition scope (not only inside the draw lambda) so that a new
        // finished stroke recomposes this Canvas and repaints immediately. Without a
        // composition-scope read the StateFlow emission updates the State but does not
        // invalidate the draw phase on its own, so the very first stroke stayed invisible
        // until an unrelated recomposition (e.g. a tool toggle) forced a redraw.
        val strokes = finishedStrokes
        Canvas(modifier = Modifier.fillMaxSize()) {
            val nativeCanvas = drawContext.canvas.nativeCanvas
            strokes.forEach { stroke: Stroke ->
                canvasStrokeRenderer.draw(
                    canvas = nativeCanvas,
                    stroke = stroke,
                    strokeToScreenTransform = identityTransform,
                )
            }
        }
        // Wet layer on top: in-progress strokes + input handling.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> createInkView(context, viewModel) },
        )
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun createInkView(context: Context, viewModel: CanvasViewModel): View {
    val rootView = FrameLayout(context)
    val inProgressStrokesView = InProgressStrokesView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        addFinishedStrokesListener(
            object : InProgressStrokesFinishedListener {
                override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
                    viewModel.onStrokesFinished(strokes.values)
                    removeFinishedStrokes(strokes.keys)
                }
            },
        )
    }
    val predictor = MotionEventPredictor.newInstance(rootView)
    rootView.setOnTouchListener(createTouchListener(inProgressStrokesView, viewModel, predictor))
    rootView.addView(inProgressStrokesView)
    return rootView
}

private fun createTouchListener(
    inProgressStrokesView: InProgressStrokesView,
    viewModel: CanvasViewModel,
    predictor: MotionEventPredictor,
): View.OnTouchListener {
    var currentPointerId: Int? = null
    var currentStrokeId: InProgressStrokeId? = null
    var erasing = false

    return View.OnTouchListener { view, event ->
        predictor.record(event)
        val predictedEvent = predictor.predict()
        try {
            val toolType = event.getToolType(event.actionIndex)
            // Palm rejection: ignore finger input entirely while stylus-only mode is on.
            if (toolType == MotionEvent.TOOL_TYPE_FINGER && viewModel.stylusOnly.value) {
                return@OnTouchListener false
            }
            // Hardware stylus eraser always erases, regardless of the selected tool.
            val erase = toolType == MotionEvent.TOOL_TYPE_ERASER ||
                viewModel.tool.value == CanvasTool.ERASER

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Single active stroke: ignore additional pointers while one is down.
                    if (currentPointerId != null) return@OnTouchListener true
                    view.requestUnbufferedDispatch(event)
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    currentPointerId = pointerId
                    if (erase) {
                        erasing = true
                        viewModel.beginEraseGesture() // one undo step per gesture
                        viewModel.eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
                    } else {
                        currentStrokeId =
                            inProgressStrokesView.startStroke(event, pointerId, viewModel.penBrush)
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val pointerId = currentPointerId ?: return@OnTouchListener false
                    val pointerIndex = event.findPointerIndex(pointerId)
                    if (pointerIndex < 0) return@OnTouchListener false
                    if (erasing) {
                        viewModel.eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
                    } else {
                        val strokeId = currentStrokeId ?: return@OnTouchListener false
                        inProgressStrokesView.addToStroke(event, pointerId, strokeId, predictedEvent)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    val pointerId = event.getPointerId(event.actionIndex)
                    if (pointerId == currentPointerId) {
                        currentStrokeId?.let { strokeId ->
                            inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                        }
                        currentPointerId = null
                        currentStrokeId = null
                        erasing = false
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    currentStrokeId?.let { inProgressStrokesView.cancelStroke(it, event) }
                    currentPointerId = null
                    currentStrokeId = null
                    erasing = false
                    true
                }

                else -> false
            }
        } finally {
            predictedEvent?.recycle()
        }
    }
}
