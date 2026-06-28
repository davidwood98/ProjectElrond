package ai.elrond.ui

import ai.elrond.domain.PalmRejection
import ai.elrond.domain.CanvasTool
import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.FingerGesture
import ai.elrond.presentation.CanvasViewModel
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import kotlin.math.abs
import kotlin.math.hypot

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
internal fun InkCanvas(
    viewModel: CanvasViewModel,
    modifier: Modifier = Modifier,
    stylusButtonTracker: StylusButtonTracker = remember(viewModel) { StylusButtonTracker(viewModel) },
) {
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> createInkView(context, viewModel, stylusButtonTracker) },
        )
    }
}

/** Dry-ink layer: renders the ViewModel's finished strokes, repainting on explicit invalidation. */
@SuppressLint("ViewConstructor")
private class DryStrokesView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create()
    // Page(world) → screen transform: a vertical translate by -scroll (FA-20). Fit-width keeps the
    // scale at 1, so this is the only adjustment between stored page coords and screen pixels.
    private val screenTransform = Matrix()
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

    /** Update the page scroll offset (px) and repaint at the new vertical position. */
    fun setScroll(scrollPx: Float) {
        screenTransform.setTranslate(0f, -scrollPx)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        strokes.forEach { stroke ->
            renderer.draw(canvas = canvas, stroke = stroke, strokeToScreenTransform = screenTransform)
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun createInkView(
    context: Context,
    viewModel: CanvasViewModel,
    stylusButtonTracker: StylusButtonTracker,
): View {
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
    val fingerGestureTracker = FingerGestureTracker(viewModel)
    rootView.setOnTouchListener(
        createTouchListener(
            inProgressStrokesView, viewModel, predictor, fingerGestureTracker, stylusButtonTracker,
        ),
    )
    // The S Pen side button fires while the pen hovers (not touching), which arrives as a generic
    // motion event, not a touch event — feed those to the button tracker too (FA-19).
    rootView.setOnGenericMotionListener { _, event -> stylusButtonTracker.onMotionEvent(event) }
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
                        combine(
                            viewModel.finishedStrokes,
                            viewModel.selection,
                            viewModel.pageScrollPx,
                        ) { strokes, sel, scroll ->
                            Triple(strokes, sel, scroll)
                        }.collect { (strokes, sel, scroll) ->
                            // Track the scroll offset every emission (cheap translate + invalidate).
                            dryStrokesView.setScroll(scroll)
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
                fingerGestureTracker.dispose()
                // stylusButtonTracker is owned (and disposed) by the caller — it's shared with the
                // always-present button observer so it keeps working across tool/overlay changes.
            }
        },
    )
    return rootView
}

private fun createTouchListener(
    inProgressStrokesView: InProgressStrokesView,
    viewModel: CanvasViewModel,
    predictor: MotionEventPredictor,
    fingerGestureTracker: FingerGestureTracker,
    stylusButtonTracker: StylusButtonTracker,
): View.OnTouchListener {
    var currentPointerId: Int? = null
    var currentStrokeId: InProgressStrokeId? = null
    var currentIsFinger = false
    var erasing = false
    // A lone finger drag scrolls vertically OR swipes horizontally to turn pages (FA-20); the axis
    // locks after a small slop so scroll and page-turn don't fight.
    var scrollPointerId: Int? = null
    var scrollStartX = 0f
    var scrollStartY = 0f
    var lastScrollY = 0f
    var scrollAxis = 0 // 0 = undecided, 1 = vertical (scroll), 2 = horizontal (page turn)
    var pageTurnFired = false

    return View.OnTouchListener { view, event ->
        // Track finger + S Pen-button gestures BEFORE the lasso bail-out, so they keep working in
        // every tool mode (FA-19). They run independently of palm rejection and of drawing.
        fingerGestureTracker.onTouchEvent(event)
        stylusButtonTracker.onMotionEvent(event)

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
                    // A second finger landing during a finger-drawn stroke means a multi-finger
                    // gesture is starting, not ink — cancel the nascent finger stroke so the tap
                    // leaves no mark (FA-19). Stylus strokes (a resting palm landing mid-stroke)
                    // are untouched: currentIsFinger is false for them.
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                        currentIsFinger && currentStrokeId != null
                    ) {
                        currentStrokeId?.let { inProgressStrokesView.cancelStroke(it, event) }
                        currentPointerId = null
                        currentStrokeId = null
                        currentIsFinger = false
                        erasing = false
                        return@OnTouchListener true
                    }
                    // Swallow a rejected finger pointer; a lone finger drag scrolls/turns pages (FA-20).
                    if (rejectFinger) {
                        if (currentPointerId == null && scrollPointerId == null &&
                            event.actionMasked == MotionEvent.ACTION_DOWN
                        ) {
                            scrollPointerId = event.getPointerId(event.actionIndex)
                            scrollStartX = event.getX(event.actionIndex)
                            scrollStartY = event.getY(event.actionIndex)
                            lastScrollY = scrollStartY
                            scrollAxis = 0
                            pageTurnFired = false
                        }
                        return@OnTouchListener true
                    }
                    // Single active stroke: ignore additional pointers while one is down.
                    if (currentPointerId != null) return@OnTouchListener true
                    view.requestUnbufferedDispatch(event)
                    val pointerIndex = event.actionIndex
                    val pointerId = event.getPointerId(pointerIndex)
                    currentPointerId = pointerId
                    currentIsFinger = isFinger
                    if (erase) {
                        erasing = true
                        viewModel.beginEraseGesture() // one undo step per gesture
                        viewModel.eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
                    } else {
                        // Pen down: hold off any prefix-/Q inactivity send until this stroke finishes,
                        // so the AI never answers mid-writing (and always gets the full question).
                        viewModel.onWritingStarted()
                        // Capture in page coords: motionEventToWorldTransform shifts the stored input
                        // down by the scroll, so the finished stroke lands at (x, y + scroll). The wet
                        // stroke still renders at the pen tip (motionEventToViewTransform = identity).
                        currentStrokeId = inProgressStrokesView.startStroke(
                            event,
                            pointerId,
                            viewModel.penBrush,
                            Matrix().apply { setTranslate(0f, viewModel.pageScrollPx.value) },
                            Matrix(),
                        )
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // A lone finger drag scrolls or swipes to turn pages (FA-20), before stylus
                    // handling. The axis locks after a small slop.
                    scrollPointerId?.let { sid ->
                        val si = event.findPointerIndex(sid)
                        if (si >= 0) {
                            val fx = event.getX(si)
                            val fy = event.getY(si)
                            val totalDx = fx - scrollStartX
                            val totalDy = fy - scrollStartY
                            if (scrollAxis == 0 &&
                                (abs(totalDx) > AXIS_LOCK_SLOP_PX || abs(totalDy) > AXIS_LOCK_SLOP_PX)
                            ) {
                                scrollAxis = if (abs(totalDx) > abs(totalDy)) 2 else 1
                                lastScrollY = fy // start scrolling from the lock point
                            }
                            if (scrollAxis == 1) {
                                viewModel.scrollBy(fy - lastScrollY)
                                lastScrollY = fy
                            } else if (scrollAxis == 2 && !pageTurnFired &&
                                abs(totalDx) > PAGE_SWIPE_THRESHOLD_PX
                            ) {
                                pageTurnFired = true
                                viewModel.turnPage(forward = totalDx < 0)
                            }
                        }
                        return@OnTouchListener true
                    }
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
                    if (pointerId == scrollPointerId) {
                        scrollPointerId = null
                        scrollAxis = 0
                        pageTurnFired = false
                    }
                    if (pointerId == currentPointerId) {
                        currentStrokeId?.let { strokeId ->
                            inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                        }
                        currentPointerId = null
                        currentStrokeId = null
                        currentIsFinger = false
                        erasing = false
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    currentStrokeId?.let { inProgressStrokesView.cancelStroke(it, event) }
                    currentPointerId = null
                    currentStrokeId = null
                    currentIsFinger = false
                    erasing = false
                    scrollPointerId = null
                    scrollAxis = 0
                    pageTurnFired = false
                    true
                }

                else -> false
            }
        } finally {
            predictedEvent?.recycle()
        }
    }
}

/**
 * Detects multi-finger tap gestures (FA-19) from raw [MotionEvent]s and dispatches them to
 * [CanvasViewModel.onFingerGesture]. Runs independently of palm rejection — it's fed every event
 * regardless of the stylus-only setting, so a deliberate 2-/3-finger tap is recognised whether
 * fingers draw or not. A resting palm or a two-finger drag is rejected by the duration + movement
 * gates; any stylus pointer in the gesture disqualifies it (it's a pen interaction, not a tap).
 *
 * A single tap waits out [DOUBLE_TAP_WINDOW_MS] to disambiguate it from a double tap — but only when
 * that finger count actually has a double-tap action bound ([CanvasViewModel.isDoubleTapBound]);
 * otherwise the single fires immediately so common actions (e.g. a 3-finger Redo) feel instant.
 *
 * Not a [android.view.GestureDetector]: that doesn't expose multi-pointer tap counts, so this is a
 * small manual state machine. Lives in the View layer (touches [MotionEvent]); the binding logic it
 * calls is unit-tested on the ViewModel, so this is device/manual-verified like the other ink flows.
 */
private class FingerGestureTracker(private val viewModel: CanvasViewModel) {
    private val handler = Handler(Looper.getMainLooper())

    // State for the gesture currently in progress (first finger down → all fingers up).
    private var gestureStartTime = 0L
    private var maxFingerCount = 0
    private var stylusInvolved = false
    private var movedTooFar = false
    private val startPositions = HashMap<Int, FloatArray>() // pointerId -> [x, y] at its down

    // State spanning gestures, for double-tap detection.
    private var lastTapFingerCount = 0
    private var lastTapTime = 0L
    private var pendingSingleTap: Runnable? = null

    fun onTouchEvent(event: MotionEvent) {
        if (!viewModel.fingerGesturesEnabled.value) {
            resetGesture()
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetGesture()
                gestureStartTime = event.eventTime
                recordPointer(event, event.actionIndex)
                updateCounts(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                recordPointer(event, event.actionIndex)
                updateCounts(event)
            }
            MotionEvent.ACTION_MOVE -> checkMovement(event)
            MotionEvent.ACTION_UP -> {
                checkMovement(event)
                evaluateTap(event.eventTime)
                resetGesture()
            }
            MotionEvent.ACTION_CANCEL -> resetGesture()
        }
    }

    /** Cancels any pending delayed single-tap. Call on detach so no gesture fires after teardown. */
    fun dispose() {
        pendingSingleTap?.let { handler.removeCallbacks(it) }
        pendingSingleTap = null
    }

    private fun recordPointer(event: MotionEvent, index: Int) {
        startPositions[event.getPointerId(index)] = floatArrayOf(event.getX(index), event.getY(index))
    }

    private fun updateCounts(event: MotionEvent) {
        var fingers = 0
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER) fingers++ else stylusInvolved = true
        }
        if (fingers > maxFingerCount) maxFingerCount = fingers
    }

    private fun checkMovement(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val start = startPositions[event.getPointerId(i)] ?: continue
            if (hypot(event.getX(i) - start[0], event.getY(i) - start[1]) > TAP_MAX_MOVEMENT_PX) {
                movedTooFar = true
                return
            }
        }
    }

    private fun evaluateTap(upTime: Long) {
        if (stylusInvolved || movedTooFar) return
        if (upTime - gestureStartTime > TAP_MAX_DURATION_MS) return
        val count = maxFingerCount
        if (count != 2 && count != 3) return
        handleTap(count, upTime)
    }

    private fun handleTap(count: Int, now: Long) {
        // A second tap of the same finger count within the window upgrades to a double tap.
        if (lastTapFingerCount == count && now - lastTapTime < DOUBLE_TAP_WINDOW_MS) {
            pendingSingleTap?.let { handler.removeCallbacks(it) }
            pendingSingleTap = null
            lastTapFingerCount = 0
            lastTapTime = 0L
            viewModel.onFingerGesture(doubleGesture(count))
            return
        }
        lastTapFingerCount = count
        lastTapTime = now
        // No double-tap bound for this count → fire the single immediately (no need to wait).
        if (!viewModel.isDoubleTapBound(count)) {
            lastTapFingerCount = 0
            lastTapTime = 0L
            viewModel.onFingerGesture(singleGesture(count))
            return
        }
        // Double-tap is bound: defer the single until the window passes without a second tap.
        val runnable = Runnable {
            pendingSingleTap = null
            lastTapFingerCount = 0
            lastTapTime = 0L
            viewModel.onFingerGesture(singleGesture(count))
        }
        pendingSingleTap = runnable
        handler.postDelayed(runnable, DOUBLE_TAP_WINDOW_MS)
    }

    private fun resetGesture() {
        gestureStartTime = 0L
        maxFingerCount = 0
        stylusInvolved = false
        movedTooFar = false
        startPositions.clear()
    }

    private companion object {
        const val TAP_MAX_DURATION_MS = 250L
        const val TAP_MAX_MOVEMENT_PX = 24f
        const val DOUBLE_TAP_WINDOW_MS = 300L

        fun singleGesture(count: Int): FingerGesture =
            if (count == 3) FingerGesture.ThreeFingerTap else FingerGesture.TwoFingerTap

        fun doubleGesture(count: Int): FingerGesture =
            if (count == 3) FingerGesture.ThreeFingerDoubleTap else FingerGesture.TwoFingerDoubleTap
    }
}

/**
 * Detects S Pen side-button gestures (FA-19) — press-and-hold (momentary), single click, and double
 * click — from the [MotionEvent.BUTTON_STYLUS_PRIMARY] state, and dispatches them to the
 * [CanvasViewModel]. Fed from both the touch listener (button pressed while drawing) and the
 * generic-motion listener (button pressed while hovering, the pen not touching the screen).
 *
 * Edge-detected on `buttonState` rather than relying solely on [MotionEvent.ACTION_BUTTON_PRESS], so
 * it works whether the device reports the change via the dedicated button action or via the button
 * bits on a hover/move event. A hold begins after [HOLD_THRESHOLD_MS] of the button staying down; a
 * release before that is a click. A single click waits out [DOUBLE_CLICK_WINDOW_MS] to rule out a
 * double — but only when a double click is bound (else it fires immediately). If the pen leaves
 * range with the button still down (hover exit), the hold is ended so it can't get stuck.
 *
 * Device/manual-verified like the other ink flows (touches [MotionEvent]); the bound actions it
 * calls are unit-tested on the ViewModel.
 */
internal class StylusButtonTracker(private val viewModel: CanvasViewModel) {
    private val handler = Handler(Looper.getMainLooper())

    private var buttonDown = false
    private var holdActive = false
    private var pendingHold: Runnable? = null

    private var lastClickTime = 0L
    private var pendingSingleClick: Runnable? = null

    /** Returns true when a button-only event was consumed (so the generic listener can claim it). */
    fun onMotionEvent(event: MotionEvent): Boolean {
        if (!viewModel.stylusButtonEnabled.value) {
            if (buttonDown || holdActive) reset()
            return false
        }
        // The pen left detection range — end any momentary hold so it can't stick down.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT && buttonDown) {
            onButtonUp(event.eventTime)
            buttonDown = false
            return false
        }
        val nowDown = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (nowDown != buttonDown) {
            buttonDown = nowDown
            if (nowDown) onButtonDown(event.eventTime) else onButtonUp(event.eventTime)
        }
        // Consume the dedicated button actions (no draw payload) to suppress any system default.
        return event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS ||
            event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
    }

    fun dispose() {
        pendingHold?.let { handler.removeCallbacks(it) }
        pendingSingleClick?.let { handler.removeCallbacks(it) }
        pendingHold = null
        pendingSingleClick = null
        if (holdActive) viewModel.onStylusHoldEnd()
        holdActive = false
    }

    private fun onButtonDown(@Suppress("UNUSED_PARAMETER") downTime: Long) {
        // If a click is already pending, this press is the second half of a (potential) double
        // click — don't arm a hold for it, or the short hold threshold could swallow the double.
        if (lastClickTime != 0L) return
        val runnable = Runnable {
            pendingHold = null
            holdActive = true
            viewModel.onStylusHoldStart()
        }
        pendingHold = runnable
        handler.postDelayed(runnable, HOLD_THRESHOLD_MS)
    }

    private fun onButtonUp(upTime: Long) {
        pendingHold?.let { handler.removeCallbacks(it) }
        pendingHold = null
        if (holdActive) {
            // It was a hold, not a click — end the momentary tool, swallow any click state.
            holdActive = false
            lastClickTime = 0L
            viewModel.onStylusHoldEnd()
            return
        }
        // A quick press-release: a click. Disambiguate single vs double.
        if (lastClickTime != 0L && upTime - lastClickTime < DOUBLE_CLICK_WINDOW_MS) {
            pendingSingleClick?.let { handler.removeCallbacks(it) }
            pendingSingleClick = null
            lastClickTime = 0L
            viewModel.onStylusClick(doubleClick = true)
            return
        }
        lastClickTime = upTime
        if (!viewModel.isStylusDoubleClickBound()) {
            lastClickTime = 0L
            viewModel.onStylusClick(doubleClick = false)
            return
        }
        val runnable = Runnable {
            pendingSingleClick = null
            lastClickTime = 0L
            viewModel.onStylusClick(doubleClick = false)
        }
        pendingSingleClick = runnable
        handler.postDelayed(runnable, DOUBLE_CLICK_WINDOW_MS)
    }

    private fun reset() {
        pendingHold?.let { handler.removeCallbacks(it) }
        pendingSingleClick?.let { handler.removeCallbacks(it) }
        pendingHold = null
        pendingSingleClick = null
        lastClickTime = 0L
        if (holdActive) viewModel.onStylusHoldEnd()
        holdActive = false
        buttonDown = false
    }

    private companion object {
        // Short so the eraser engages quickly on hold — a click just has to release faster than this.
        const val HOLD_THRESHOLD_MS = 150L
        const val DOUBLE_CLICK_WINDOW_MS = 300L
    }
}

/** Finger-swipe tuning (FA-20): how far the drag travels before the scroll/turn axis locks, and how
 *  far a horizontal swipe must travel to turn the page. Raw px (device-tunable). */
private const val AXIS_LOCK_SLOP_PX = 20f
private const val PAGE_SWIPE_THRESHOLD_PX = 140f
