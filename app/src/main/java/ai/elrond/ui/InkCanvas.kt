package ai.elrond.ui

import ai.elrond.canvas.PalmRejection
import ai.elrond.canvas.CanvasTool
import ai.elrond.canvas.CanvasStroke
import ai.elrond.presentation.CanvasViewModel
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Handwriting canvas with two ink layers:
 *  - Wet ink: [InProgressStrokesView] renders in-progress strokes with front-buffered,
 *    low-latency rendering (120Hz-capable) plus motion prediction. Its surface is warmed via
 *    `eagerInit()` on attach so the first stylus touch isn't eaten by lazy surface creation.
 *  - Dry ink: finished strokes from [CanvasViewModel] (minus any lasso-selected ones), drawn by
 *    [DryStrokesView].
 *
 * The lasso-selected strokes (live move/scale + the faded origin ghost) are NOT drawn here — they
 * render in the Compose `SelectionLayer` overlay. The wet-ink view is a front-buffered SurfaceView
 * composited on top of the whole window, and a plain sibling View beneath it does not reliably
 * re-composite during a fast drag on-device (FA-10); Compose does, so the moving selection lives
 * there. The dry layer just excludes the selected strokes so they aren't drawn twice.
 *
 * Both layers are plain Android Views inside one [FrameLayout]. The dry layer is deliberately NOT a
 * Compose `Canvas`: a Compose Canvas only repaints when composition is invalidated, and a bare
 * `StateFlow` emission (a finished stroke) does not reliably wake a recomposition on its own — which
 * left the very first stroke of a freshly opened page invisible until some unrelated recompose
 * forced a redraw. The views below collect the flows directly and call [View.invalidate], which
 * schedules a draw via the Choreographer regardless of Compose.
 *
 * Input handling supports S Pen pressure/tilt (via the pressure-pen stock brush), palm rejection
 * (finger input ignored while stylus-only mode is on), the hardware stylus eraser
 * ([MotionEvent.TOOL_TYPE_ERASER]), and a drawn eraser tool. In [CanvasTool.LASSO] mode the Compose
 * selection overlay owns input, so this listener never draws ink.
 */
@Composable
fun InkCanvas(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> createInkView(context, viewModel) },
        )
    }
}

/** Dry-ink layer: renders the ViewModel's finished strokes, repainting on explicit invalidation. */
@SuppressLint("ViewConstructor")
private class DryStrokesView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create()
    private val identityTransform = Matrix()
    private var strokes: List<Stroke> = emptyList()

    init {
        // A plain View can skip onDraw as an optimisation; ensure it always draws our ink.
        setWillNotDraw(false)
    }

    /** Replace the dry strokes and repaint immediately (independent of Compose recomposition). */
    fun setStrokes(value: List<Stroke>) {
        strokes = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { stroke ->
            renderer.draw(canvas = canvas, stroke = stroke, strokeToScreenTransform = identityTransform)
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun createInkView(context: Context, viewModel: CanvasViewModel): View {
    val rootView = FrameLayout(context)
    val matchParent = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )
    val dryStrokesView = DryStrokesView(context).apply { layoutParams = matchParent }
    val inProgressStrokesView = InProgressStrokesView(context).apply {
        layoutParams = matchParent
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
    // Dry layer (bottom), wet ink on top. The lasso-selected strokes (live move/scale + the faded
    // origin ghost) are drawn by the Compose SelectionLayer overlay, NOT here: the wet-ink view is a
    // front-buffered SurfaceView composited on top of the whole window, and a plain sibling View
    // beneath it does not reliably re-composite during a fast drag on-device (FA-10) — Compose
    // (which already paints the selection box smoothly every frame) does. The dry layer just excludes
    // the selected strokes so they aren't drawn twice.
    rootView.addView(dryStrokesView)
    rootView.addView(inProgressStrokesView)

    // Drive the dry layer straight off the StateFlows while the screen is at least STARTED, so a
    // finished stroke repaints via invalidate() without waiting on a Compose recomposition.
    rootView.addOnAttachStateChangeListener(
        object : View.OnAttachStateChangeListener {
            private var job: Job? = null

            override fun onViewAttachedToWindow(v: View) {
                // Warm the front-buffered rendering surface NOW, on screen entry, instead of letting
                // it initialise lazily on the first startStroke. Lazy init on first touch creates the
                // surface and relayouts the window, which drops that first input event across the
                // WHOLE window (FA-8 issue 2). eagerInit() is idempotent and UI-thread-only.
                inProgressStrokesView.eagerInit()
                val owner = v.findViewTreeLifecycleOwner() ?: return
                job = owner.lifecycleScope.launch {
                    owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        // Keys for the last dry-layer rebuild, so we rebuild + repaint ONLY when the
                        // stroke list or the selected set actually changes.
                        var splitKeyStrokes: List<CanvasStroke>? = null
                        var splitKeyIds: Set<String> = emptySet()
                        combine(viewModel.finishedStrokes, viewModel.selection) { strokes, sel ->
                            strokes to sel
                        }.collect { (strokes, sel) ->
                            val selectedIds = sel?.ids ?: emptySet()
                            // setStrokes() invalidate()s, which repaints every dry stroke from scratch
                            // via CanvasStrokeRenderer.draw. The combine also fires on every
                            // transform-only emission (each lasso-drag frame updates selection.transform
                            // but not the stroke list or the selected ids) — so calling setStrokes there
                            // would redraw the whole page per frame. Gate it on a real change.
                            if (strokes !== splitKeyStrokes || selectedIds != splitKeyIds) {
                                splitKeyStrokes = strokes
                                splitKeyIds = selectedIds
                                dryStrokesView.setStrokes(
                                    strokes.filterNot { it.id in selectedIds }.map { it.stroke },
                                )
                            }
                        }
                    }
                }
            }

            override fun onViewDetachedFromWindow(v: View) {
                job?.cancel()
                job = null
            }
        },
    )
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
        // In lasso-select mode the Compose selection overlay owns all input (it sits above this
        // view and consumes the gesture); never draw ink here. This is a belt-and-braces guard.
        if (viewModel.tool.value == CanvasTool.LASSO) return@OnTouchListener false

        predictor.record(event)
        val predictedEvent = predictor.predict()
        try {
            val toolType = event.getToolType(event.actionIndex)
            val isFinger = toolType == MotionEvent.TOOL_TYPE_FINGER
            // Palm rejection: when a finger pointer should be ignored we must still CONSUME the
            // event (return true) rather than decline it. Returning false makes the framework
            // withdraw the whole gesture — which cancels an in-progress stylus stroke (a palm
            // landing mid-stroke) and drops the first stroke when a finger touches first. So we
            // swallow finger pointers here and let any stylus pointer in the same gesture draw.
            val rejectFinger = PalmRejection.shouldReject(isFinger, viewModel.stylusOnly.value)
            // Hardware stylus eraser always erases, regardless of the selected tool.
            val erase = toolType == MotionEvent.TOOL_TYPE_ERASER ||
                viewModel.tool.value == CanvasTool.ERASER

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    // Swallow a rejected finger pointer without disturbing any active stroke.
                    if (rejectFinger) return@OnTouchListener true
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
                    // Only the tracked (stylus) pointer advances the stroke; finger pointers in
                    // the same event are ignored. Keep consuming so the gesture stays alive.
                    val pointerId = currentPointerId ?: return@OnTouchListener true
                    val pointerIndex = event.findPointerIndex(pointerId)
                    if (pointerIndex < 0) return@OnTouchListener true
                    if (erasing) {
                        viewModel.eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
                    } else {
                        val strokeId = currentStrokeId ?: return@OnTouchListener true
                        inProgressStrokesView.addToStroke(event, pointerId, strokeId, predictedEvent)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    // Only finish when the tracked pointer lifts; a finger lifting is ignored.
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
