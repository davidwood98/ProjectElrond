package ai.elrond.ui

import ai.elrond.BuildConfig
import ai.elrond.data.StrokeSerialization
import ai.elrond.domain.InkLineType
import ai.elrond.domain.PalmRejection
import ai.elrond.domain.PageLayer
import ai.elrond.domain.PageTransform
import ai.elrond.domain.CanvasTool
import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.FingerGesture
import ai.elrond.domain.SelectionBounds
import ai.elrond.domain.StrokeSelection
import ai.elrond.presentation.CanvasViewModel
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RenderNode
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
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
    /** Called on any touch that reaches the canvas — used to commit/close the inline title editor. */
    onInteract: () -> Unit = {},
) {
    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context -> createInkView(context, viewModel, stylusButtonTracker, onInteract) },
        )
    }
}

/**
 * Dry-ink layer: renders the ViewModel's finished strokes (stored in page coordinates).
 *
 * The page transform is applied as **per-frame GPU view properties** (`translationX/Y`, `scaleX/Y`),
 * NOT a matrix recomputed in `onDraw` + `invalidate()`. This is the FA-10 lesson applied to scroll:
 * a translate-in-draw + `invalidate()` did not produce per-frame redraws during a scroll drag (the
 * ink stayed frozen and snapped to the scrolled position on release), exactly as a Compose transform
 * inside a draw lambda froze the lasso ink. Driving `translationY` instead is a cheap composite the
 * framework re-applies every frame — the strokes rasterise once and the layer is just re-positioned.
 *
 * The view is laid out at the **full page size** (width × A-ratio height), positioned at the page's
 * screen origin, so translating it up by the scroll reveals the off-screen (lower) ink rather than
 * blank space. Strokes are drawn in page space (identity matrix); the view's transform maps page →
 * screen. Stroke-list changes still go through [setStrokes] + `invalidate()` (off Compose, so the
 * first stroke of a freshly opened page paints without waiting on a recomposition — the FA-6 fix).
 */
/** One stroke in the dry layer's flattened draw order, with the docTop of its page. */
private class DryDrawEntry(val docTop: Float, val cs: CanvasStroke)

@SuppressLint("ViewConstructor")
private class DryStrokesView(context: Context) : View(context) {
    private val renderer = CanvasStrokeRenderer.create(InkTextures.store(context.resources))
    // Each layer's strokes are stored in that page's own page-space; the view carries the document→
    // screen transform and draws each layer translated by its (open-page-relative) docTop, so a single
    // view + GPU transform renders the whole continuous vertical document (FA-20). The matrix is
    // reused per entry to avoid per-frame allocation.
    private val layerMatrix = Matrix()
    private var layers: List<PageLayer> = emptyList()
    private var excludedIds: Set<String> = emptySet()
    private var viewWidthPx = 0
    private var viewHeightPx = 0

    // RenderNode baking (page-fill perf). Finished strokes are recorded into [bakedNode]; onDraw replays
    // that node (one op) plus the few strokes added since the last fold ("tail" drawn live). A new pen
    // stroke costs O(tail), not O(all-strokes).
    //
    // Folding the tail in is INCREMENTAL: a new node replays the previous node (one O(1) drawRenderNode
    // op) then records only the tail — so a fold costs O(tail), NOT O(all). (Device measurement showed a
    // full O(N) re-record every 32 strokes was a ~70–114ms UI-thread hitch on an ~800-stroke page — the
    // remaining stutter.) This chains the nodes; once the chain reaches [MAX_CHAIN_DEPTH], or the stroke
    // set changes non-additively (erase / undo / lasso transform / selection / resize), it FLATTENS
    // (records every stroke into one fresh node — the only O(N) path, now rare). Scroll/zoom never call
    // onDraw (GPU view properties), so nothing is re-recorded then.
    private var bakedNode = RenderNode("dryBaked")
    private var bakedValid = false
    private var committedEntries: List<DryDrawEntry> = emptyList()
    private var bakedExcluded: Set<String> = emptySet()
    private var chainDepth = 0

    // Current draw order, rebuilt in setLayers (the content-change signal) instead of every onDraw, and
    // reused by onDraw. Also lets setLayers diff against the previous list to invalidate only the new
    // strokes' rectangle — so the RenderThread re-rasterises just that region, not the whole ~800-mesh
    // page (the per-stroke GPU cost that still janks a dense page, worst in landscape's larger fill).
    private var drawList: List<DryDrawEntry> = emptyList()

    init {
        // A plain View can skip onDraw as an optimisation; ensure it always draws our ink.
        setWillNotDraw(false)
        // Scale/translate about the top-left so view properties compose as screen = page·scale + offset.
        pivotX = 0f
        pivotY = 0f
    }

    /**
     * Replace the rendered page layers (minus any lasso-selected ids) and repaint. The draw list is
     * built here (the content-change signal) and reused by onDraw. We rely on HWUI's own damage diff to
     * re-rasterise only the changed ops: since [bakedNode] is an unchanged reference between folds, a
     * new pen stroke damages only its own bounds; a fold (new node) or a non-append damages the page.
     */
    fun setLayers(value: List<PageLayer>, excluded: Set<String>) {
        drawList = buildDrawList(value, excluded)
        layers = value
        excludedIds = excluded
        invalidate()
    }

    /** Applies the document → screen [transform] as GPU view properties (per-frame, no redraw). */
    fun setTransform(transform: PageTransform) {
        translationX = transform.offsetX + transform.panX // panX = transient page-turn slide
        translationY = transform.offsetY // open-page origin; layers offset by their docTop in onDraw
        scaleX = transform.scale
        scaleY = transform.scale
    }

    /** Sets the view-local size (page-space px) spanning the whole document; relayouts on change. */
    fun setViewSize(widthPx: Float, heightPx: Float) {
        val w = widthPx.toInt()
        val h = heightPx.toInt()
        if (w != viewWidthPx || h != viewHeightPx) {
            viewWidthPx = w
            viewHeightPx = h
            bakedValid = false // the node is sized to the document; re-bake at the new size
            requestLayout()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (viewWidthPx > 0 && viewHeightPx > 0) {
            setMeasuredDimension(viewWidthPx, viewHeightPx)
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    /** Draw order (page order × stroke order) for [value], excluding lasso-selected [excluded] ids. */
    private fun buildDrawList(value: List<PageLayer>, excluded: Set<String>): List<DryDrawEntry> {
        val out = ArrayList<DryDrawEntry>()
        value.forEach { layer ->
            layer.strokes.forEach { cs -> if (cs.id !in excluded) out.add(DryDrawEntry(layer.docTopPx, cs)) }
        }
        return out
    }

    /** True when [current] extends [committedEntries] with the same prefix (same stroke ref + docTop). */
    private fun extendsBaked(current: List<DryDrawEntry>): Boolean {
        if (!bakedValid || excludedIds != bakedExcluded || current.size < committedEntries.size) return false
        for (i in committedEntries.indices) {
            val b = committedEntries[i]
            val c = current[i]
            if (b.cs.stroke !== c.cs.stroke || b.docTop != c.docTop) return false
        }
        return true
    }

    private fun drawEntry(canvas: Canvas, e: DryDrawEntry) {
        layerMatrix.setTranslate(0f, e.docTop)
        renderer.draw(canvas = canvas, stroke = e.cs.stroke, strokeToScreenTransform = layerMatrix)
    }

    /** Records EVERY stroke into a fresh node (O(N)) — the rare path (non-additive change / deep chain). */
    private fun flatten(entries: List<DryDrawEntry>) {
        Trace.beginSection("DryStrokes.flatten")
        val t0 = System.nanoTime()
        val node = RenderNode("dryBaked")
        node.setPosition(0, 0, viewWidthPx.coerceAtLeast(1), viewHeightPx.coerceAtLeast(1))
        val rc = node.beginRecording()
        try {
            entries.forEach { drawEntry(rc, it) }
        } finally {
            node.endRecording()
        }
        bakedNode = node
        committedEntries = entries
        bakedExcluded = excludedIds
        bakedValid = true
        chainDepth = 0
        if (BuildConfig.DEBUG) {
            Log.d(PERF_TAG, "flatten strokes=${entries.size} ${"%.1f".format((System.nanoTime() - t0) / 1_000_000.0)}ms")
        }
        Trace.endSection()
    }

    /** Folds the tail (strokes after [committedEntries]) into a new node that replays the old one (O(tail)). */
    private fun foldTail(current: List<DryDrawEntry>) {
        Trace.beginSection("DryStrokes.foldTail")
        val start = committedEntries.size
        val node = RenderNode("dryBaked")
        node.setPosition(0, 0, viewWidthPx.coerceAtLeast(1), viewHeightPx.coerceAtLeast(1))
        val rc = node.beginRecording()
        try {
            rc.drawRenderNode(bakedNode) // replay the prior chain in one op — no re-record of old strokes
            for (i in start until current.size) drawEntry(rc, current[i])
        } finally {
            node.endRecording()
        }
        bakedNode = node
        committedEntries = current
        chainDepth++
        Trace.endSection()
    }

    /**
     * Re-bake when the window becomes visible again. Backgrounding the app frees the renderer's
     * hardware resources; the baked node chain's recorded ink-mesh ops then replay blank on
     * return, while [extendsBaked] still passes (same stroke list) so nothing re-records — the
     * page's saved strokes stayed invisible after a tray reopen until some layer change forced a
     * flatten (device bug, 2026-07-07). One O(N) flatten on resume restores them.
     */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && bakedValid) {
            bakedValid = false
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (viewWidthPx <= 0 || viewHeightPx <= 0) return
        Trace.beginSection("DryStrokes.onDraw")
        try {
            val current = drawList
            if (!extendsBaked(current)) {
                flatten(current) // non-additive change (erase/undo/transform/selection/resize) or first draw
            } else if (current.size - committedEntries.size >= FOLD_TAIL_THRESHOLD) {
                // Enough new strokes accumulated to fold them into the node so onDraw stays O(1)+tail.
                if (chainDepth >= MAX_CHAIN_DEPTH) flatten(current) else foldTail(current)
            }
            canvas.drawRenderNode(bakedNode) // replays all committed strokes in one op
            // Any not-yet-folded tail (< FOLD_TAIL_THRESHOLD) is drawn live on top.
            for (i in committedEntries.size until current.size) drawEntry(canvas, current[i])
        } finally {
            Trace.endSection()
        }
    }

    private companion object {
        /** Fold the tail into the node once it reaches this many strokes (each fold costs O(tail)). */
        const val FOLD_TAIL_THRESHOLD = 32

        /** Flatten (one O(N) record) once the incremental-fold chain gets this deep, to bound replay. */
        const val MAX_CHAIN_DEPTH = 16
        private const val PERF_TAG = "ElrondPerf"
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun createInkView(
    context: Context,
    viewModel: CanvasViewModel,
    stylusButtonTracker: StylusButtonTracker,
    onInteract: () -> Unit,
): View {
    val rootView = FrameLayout(context)
    val matchParent = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT,
    )
    val dryStrokesView = DryStrokesView(context).apply { layoutParams = matchParent }
    val inProgressStrokesView = InProgressStrokesView(context).apply {
        layoutParams = matchParent
        // Resolve the textured pencil family's bitmaps in the wet layer too (FA-23).
        textureBitmapStore = InkTextures.store(context.resources)
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
            onInteract,
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
                        // Keys for the last dry-layer rebuild, so we re-set the layers + repaint ONLY
                        // when the page set/strokes or the selected set actually changes (not on a
                        // transform-only emission, e.g. a scroll/zoom/lasso-drag frame).
                        var lastLayers: List<PageLayer>? = null
                        var lastIds: Set<String> = emptySet()
                        combine(
                            viewModel.pageLayers,
                            viewModel.selection,
                            viewModel.documentTransform,
                            viewModel.documentHeightPx,
                        ) { layers, sel, transform, docHeight ->
                            DryLayerState(layers, sel?.ids ?: emptySet(), transform, docHeight)
                        }.collect { (layers, selectedIds, transform, docHeight) ->
                            // Lay the dry view out over the WHOLE document (page-space units, so the GPU
                            // scale below maps it to screen — zoom-ready), then apply the document→screen
                            // transform as GPU properties every emission (a cheap composite, no redraw) —
                            // this is what makes scroll/zoom track per-frame and centres the page in
                            // landscape (FA-20 B1/B2). One view renders every page of the document.
                            val pageScreenW = (rootView.width - 2 * transform.offsetX).coerceAtLeast(0f)
                            val scale = if (transform.scale != 0f) transform.scale else 1f
                            val pageSpaceW = pageScreenW / scale
                            val docHeightSpace = if (docHeight > 0f) docHeight else pageSpaceW * PageTransform.ASPECT_RATIO
                            dryStrokesView.setViewSize(pageSpaceW, docHeightSpace)
                            dryStrokesView.setTransform(transform)
                            // setLayers() invalidate()s, repainting every dry stroke; gate it on a real
                            // change so a transform-only emission doesn't redraw the whole document.
                            if (layers !== lastLayers || selectedIds != lastIds) {
                                lastLayers = layers
                                lastIds = selectedIds
                                dryStrokesView.setLayers(layers, selectedIds)
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

/** The combined dry-layer render inputs (FA-20), destructured in the collector. */
private data class DryLayerState(
    val layers: List<PageLayer>,
    val selectedIds: Set<String>,
    val transform: PageTransform,
    val docHeight: Float,
)

/** Finger-pointer count, centroid, and mean spread from the centroid — the inputs a pinch needs (FA-20). */
private class FingerSpan(val count: Int, val cx: Float, val cy: Float, val spread: Float)

private fun fingerSpan(event: MotionEvent, excludeIndex: Int = -1): FingerSpan {
    var sumX = 0f
    var sumY = 0f
    var n = 0
    for (i in 0 until event.pointerCount) {
        if (i == excludeIndex) continue
        if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
        sumX += event.getX(i)
        sumY += event.getY(i)
        n++
    }
    if (n == 0) return FingerSpan(0, 0f, 0f, 0f)
    val cx = sumX / n
    val cy = sumY / n
    var spread = 0f
    for (i in 0 until event.pointerCount) {
        if (i == excludeIndex) continue
        if (event.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) continue
        spread += hypot(event.getX(i) - cx, event.getY(i) - cy)
    }
    return FingerSpan(n, cx, cy, spread / n)
}

/** Press-and-hold duration to select an AI box (FA-21), and the movement that aborts it (a stroke). */
private const val AI_NOTE_HOLD_MS = 800L
private const val HOLD_MOVE_SLOP_PX = 24f

/**
 * Hold-to-straighten (FA-23): a stroke at least [STRAIGHTEN_MIN_LENGTH_PX] of displacement whose
 * pen then rests within [STRAIGHTEN_SLOP_PX] for [STRAIGHTEN_HOLD_MS] snaps to a straight line.
 */
private const val STRAIGHTEN_HOLD_MS = 400L // device-tuned down from 600ms (2026-07-07 feedback)
private const val STRAIGHTEN_SLOP_PX = 8f
private const val STRAIGHTEN_MIN_LENGTH_PX = 48f

/** Screen-px slop around a selection box so a DOWN on its edge-straddling resize handles isn't
 *  treated as a draw/deselect (FA-21). */
private const val SELECTION_TOUCH_MARGIN_PX = 44f

private fun createTouchListener(
    inProgressStrokesView: InProgressStrokesView,
    viewModel: CanvasViewModel,
    predictor: MotionEventPredictor,
    fingerGestureTracker: FingerGestureTracker,
    stylusButtonTracker: StylusButtonTracker,
    onInteract: () -> Unit,
): View.OnTouchListener {
    var currentPointerId: Int? = null
    var currentStrokeId: InProgressStrokeId? = null
    var currentIsFinger = false
    var erasing = false
    // FA-23: a non-solid line type bypasses the wet ink layer (it can't dash) — the points are fed
    // to the ViewModel and a Compose overlay renders the pattern live; pen-up bakes the stroke.
    var patternActive = false
    var patternStartTime = 0L
    // FA-23 hold-to-straighten: once a drawn stroke is longer than [STRAIGHTEN_MIN_LENGTH_PX] and
    // the pen then rests within [STRAIGHTEN_SLOP_PX] for [STRAIGHTEN_HOLD_MS], the stroke snaps to
    // a straight line (the wet/pattern stroke is cancelled and the VM preview takes over); further
    // movement adjusts the endpoint, lift commits.
    var straightening = false
    var straightenRunnable: Runnable? = null
    var strokeStartScreenX = 0f
    var strokeStartScreenY = 0f
    var straightenAnchorX = 0f
    var straightenAnchorY = 0f
    var lastMoveScreenX = 0f
    var lastMoveScreenY = 0f
    // A lone finger drag scrolls vertically OR swipes horizontally to turn pages (FA-20); the axis
    // locks after a small slop so scroll and page-turn don't fight.
    var scrollPointerId: Int? = null
    var scrollStartX = 0f
    var scrollStartY = 0f
    var lastScrollY = 0f
    var lastScrollX = 0f
    var scrollAxis = 0 // 0 = undecided, 1 = vertical (scroll), 2 = horizontal (page turn / pan)
    // When the horizontal axis locks while zoomed in (page wider than the viewport) the drag PANS the
    // page instead of turning it; captured once at lock so release knows not to spring a page turn.
    var horizontalIsPan = false
    // Scroll mode (palm rejection on): a lone finger doesn't draw, so a clean TAP can select the AI
    // box under it (or deselect on empty page) — resolved on UP when no axis locked. Holds the box
    // hit-tested under the scroll-finger DOWN, or null if it landed on empty page.
    var scrollDownNoteId: String? = null
    // FA-24: link box hit-tested under the scroll-finger DOWN — a clean tap OPENS it (vs an AI box,
    // which a tap selects). Checked before the AI box, matching the render order (links on top).
    var scrollDownLinkId: String? = null
    // Two-finger pinch zoom (FA-20): tracks the previous finger spread + centroid between frames.
    var pinching = false
    var pinchPrevSpread = 0f
    var pinchPrevCx = 0f
    var pinchPrevCy = 0f
    // FA-21: a stationary press-and-hold ([AI_NOTE_HOLD_MS]) over an AI box selects it (so it can be
    // moved/scaled via the shared lasso chrome). Used by the pen/stylus and by a finger in finger-draw
    // mode — moving cancels the hold, which is why a deselected AI box is passive (no pointer input)
    // and the pen writes over it. (In scroll mode a finger doesn't draw, so a plain tap selects — see
    // [scrollDownNoteId] — and a hold isn't needed.)
    val holdHandler = Handler(Looper.getMainLooper())
    var pendingHold: Runnable? = null
    var holdDownX = 0f
    var holdDownY = 0f
    var holdConsumed = false // a hold fired: cancel (don't finish) this stroke so it leaves no mark
    // FA-24: pen DOWN landed on a link box. A clean UP (no hold fired, no movement past the slop)
    // is a TAP that opens the target — there is no other tap-to-open gesture in the canvas, so this
    // is deliberately conservative: any real stroke over the box disqualifies it (write-over wins).
    var downLinkId: String? = null
    var linkTapEligible = false
    fun cancelPendingHold() {
        pendingHold?.let { holdHandler.removeCallbacks(it) }
        pendingHold = null
    }
    // FA-24 device feedback: a stationary finger HOLD on a link (scroll mode) selects it — a tap
    // opens, so unlike AI boxes (where a tap already selects) a link needs the hold to be
    // selectable by finger at all. Same AI_NOTE_HOLD_MS; a fired hold suppresses the UP tap.
    var pendingScrollHold: Runnable? = null
    var scrollHoldConsumed = false
    fun cancelPendingScrollHold() {
        pendingScrollHold?.let { holdHandler.removeCallbacks(it) }
        pendingScrollHold = null
    }
    fun cancelStraightenTimer() {
        straightenRunnable?.let { holdHandler.removeCallbacks(it) }
        straightenRunnable = null
    }

    return View.OnTouchListener { view, event ->
        // Any touch that reaches the canvas commits + closes the inline title editor (FA-20). Tapping
        // the title field itself doesn't reach here (the header consumes it), so cursor placement works.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) onInteract()
        // Track finger + S Pen-button gestures BEFORE the lasso bail-out, so they keep working in
        // every tool mode (FA-19). They run independently of palm rejection and of drawing.
        fingerGestureTracker.onTouchEvent(event)
        stylusButtonTracker.onMotionEvent(event)

        // In lasso-select mode the Compose selection overlay owns all input (it sits above this
        // view and consumes the gesture); never draw ink here. This is a belt-and-braces guard.
        if (viewModel.tool.value == CanvasTool.LASSO) return@OnTouchListener false

        predictor.record(event)
        // Motion prediction is DISABLED: the predicted (speculative) event routinely produced points
        // that duplicate the last real point's (position, time), which androidx.ink rejects with
        // "Inputs must not have duplicate position and elapsed_time" (37× in one writing session on the
        // device). Those were only speculative points — the real pen points still enqueue, so no real
        // ink was ever lost — but it was log noise + occasional speculative flicker. Front-buffered
        // low-latency rendering already keeps the pen-to-ink gap small, so the ~1-frame prediction is a
        // cheap thing to drop. `record()` is kept so re-enabling is one line: `predictor.predict()`.
        val predictedEvent: android.view.MotionEvent? = null
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
                    // Pinch zoom (FA-20): two or more fingers → enter pinch mode, cancelling any nascent
                    // finger stroke + single-finger scroll. Skipped while a stylus stroke is active.
                    val span = fingerSpan(event)
                    if (span.count >= 2 && ((currentStrokeId == null && !patternActive) || currentIsFinger)) {
                        if (currentIsFinger && currentStrokeId != null) {
                            currentStrokeId?.let { inProgressStrokesView.cancelStroke(it, event) }
                        }
                        if (patternActive && currentIsFinger) {
                            viewModel.cancelPatternStroke()
                            patternActive = false
                        }
                        if (currentIsFinger) {
                            cancelStraightenTimer()
                            if (straightening) {
                                viewModel.cancelStraightLine()
                                straightening = false
                            }
                        }
                        currentPointerId = null
                        currentStrokeId = null
                        currentIsFinger = false
                        erasing = false
                        scrollPointerId = null
                        scrollAxis = 0
                        cancelPendingScrollHold()
                        scrollHoldConsumed = false
                        pinching = true
                        pinchPrevSpread = span.spread
                        pinchPrevCx = span.cx
                        pinchPrevCy = span.cy
                        return@OnTouchListener true
                    }
                    // A second finger landing during a finger-drawn stroke means a multi-finger
                    // gesture is starting, not ink — cancel the nascent finger stroke so the tap
                    // leaves no mark (FA-19). Stylus strokes (a resting palm landing mid-stroke)
                    // are untouched: currentIsFinger is false for them.
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN &&
                        currentIsFinger && (currentStrokeId != null || patternActive)
                    ) {
                        currentStrokeId?.let { inProgressStrokesView.cancelStroke(it, event) }
                        if (patternActive) {
                            viewModel.cancelPatternStroke()
                            patternActive = false
                        }
                        cancelStraightenTimer()
                        if (straightening) {
                            viewModel.cancelStraightLine()
                            straightening = false
                        }
                        currentPointerId = null
                        currentStrokeId = null
                        currentIsFinger = false
                        erasing = false
                        downLinkId = null
                        linkTapEligible = false
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
                            lastScrollX = scrollStartX
                            scrollAxis = 0
                            // A finger doesn't draw in scroll mode, so a clean tap can act on the
                            // box under it (resolved on UP): a link box opens, an AI box selects.
                            // A drag past the slop locks an axis and scrolls instead, leaving any
                            // selection alone. Links are checked first (they render on top).
                            val t = viewModel.pageTransform.value
                            val downPageX = t.screenToPageX(scrollStartX)
                            val downPageY = t.screenToPageY(scrollStartY)
                            scrollDownLinkId = viewModel.linkAt(downPageX, downPageY)
                            scrollDownNoteId = if (scrollDownLinkId == null) {
                                viewModel.aiNoteAt(downPageX, downPageY)
                            } else {
                                null
                            }
                            // Arm the finger hold-to-select for a link (tap = open, hold = select /
                            // broken-link menu). Movement past the slop or an axis lock cancels it.
                            scrollHoldConsumed = false
                            scrollDownLinkId?.let { linkId ->
                                val runnable = Runnable {
                                    pendingScrollHold = null
                                    scrollHoldConsumed = true
                                    viewModel.holdLink(linkId)
                                }
                                pendingScrollHold = runnable
                                holdHandler.postDelayed(runnable, AI_NOTE_HOLD_MS)
                            }
                        }
                        return@OnTouchListener true
                    }
                    // Single active stroke: ignore additional pointers while one is down.
                    if (currentPointerId != null) return@OnTouchListener true
                    val pointerIndex = event.actionIndex
                    val downX = event.getX(pointerIndex)
                    val downY = event.getY(pointerIndex)
                    val t = viewModel.pageTransform.value
                    val pageX = t.screenToPageX(downX)
                    val pageY = t.screenToPageY(downY)
                    // FA-21: a non-erase DOWN on the current selection — its box OR the resize handles
                    // that straddle the edges (hence the screen-space margin) — belongs to the
                    // selection chrome above, so decline and let its drag run (no stray stroke). A DOWN
                    // elsewhere deselects (writing away). The ERASER never declines, so it can still
                    // erase ink that lies under the selection box.
                    viewModel.selection.value?.let { sel ->
                        val b = sel.displayBounds
                        // Same minimum-size inflation as the drawn SelectionBox (FA-23), so a DOWN
                        // anywhere inside the visible box is declined to its drag, never a stroke.
                        val screenBox = StrokeSelection.inflatedToMinimum(
                            SelectionBounds(
                                left = t.pageToScreenX(b.left),
                                top = t.pageToScreenY(b.top),
                                right = t.pageToScreenX(b.right),
                                bottom = t.pageToScreenY(b.bottom),
                            ),
                            minSize = MIN_SELECTION_BOX_DIMENSION * view.resources.displayMetrics.density,
                        )
                        val m = SELECTION_TOUCH_MARGIN_PX
                        val onChrome = downX >= screenBox.left - m &&
                            downX <= screenBox.right + m &&
                            downY >= screenBox.top - m &&
                            downY <= screenBox.bottom + m
                        if (!erase && onChrome) return@OnTouchListener false
                        viewModel.clearSelection()
                    }
                    view.requestUnbufferedDispatch(event)
                    val pointerId = event.getPointerId(pointerIndex)
                    currentPointerId = pointerId
                    currentIsFinger = isFinger
                    holdConsumed = false
                    downLinkId = null
                    linkTapEligible = false
                    if (erase) {
                        erasing = true
                        viewModel.beginEraseGesture() // one undo step per gesture
                        viewModel.eraseAt(downX, downY)
                    } else {
                        // Pen down: hold off any prefix-/Q inactivity send until this stroke finishes,
                        // so the AI never answers mid-writing (and always gets the full question).
                        viewModel.onWritingStarted()
                        // Seed the hold-to-straighten tracker for this stroke (FA-23).
                        strokeStartScreenX = downX
                        strokeStartScreenY = downY
                        straightenAnchorX = downX
                        straightenAnchorY = downY
                        lastMoveScreenX = downX
                        lastMoveScreenY = downY
                        straightening = false
                        cancelStraightenTimer()
                        if (viewModel.currentLineType() != InkLineType.SOLID) {
                            // FA-23 patterned stroke: buffer page-space points for the live overlay
                            // instead of starting a wet ink stroke (the wet layer can't dash).
                            patternActive = true
                            patternStartTime = event.eventTime
                            viewModel.beginPatternStroke()
                            viewModel.addPatternPoint(pageX, pageY, 0L, event.getPressure(pointerIndex))
                        } else {
                            // Capture in the OPEN page's coords via its transform — undo the centring
                            // offset, the scroll, and the zoom scale so the finished stroke is stored in
                            // page space. The wet stroke still renders at the pen tip
                            // (motionEventToViewTransform = identity).
                            val invScale = if (t.scale != 0f) 1f / t.scale else 1f
                            currentStrokeId = inProgressStrokesView.startStroke(
                                event,
                                pointerId,
                                StrokeSerialization.brushFor(viewModel.currentBrushSpec()),
                                Matrix().apply {
                                    setTranslate(-t.offsetX, -t.offsetY)
                                    postScale(invScale, invScale)
                                },
                                Matrix(),
                            )
                        }
                        // Over a link box (checked first — links render on top of AI boxes)? Arm the
                        // same hold (select, or the broken-link menu) and mark the DOWN tap-eligible:
                        // a clean UP with no hold and no movement opens the target (FA-24). Over an
                        // AI box? Arm the hold-to-select. Movement (a real stroke) cancels either
                        // below, so writing over the box still works (FA-21).
                        val linkId = viewModel.linkAt(pageX, pageY)
                        if (linkId != null) {
                            downLinkId = linkId
                            linkTapEligible = true
                            holdDownX = downX
                            holdDownY = downY
                            val runnable = Runnable {
                                pendingHold = null
                                holdConsumed = true
                                linkTapEligible = false
                                viewModel.holdLink(linkId)
                            }
                            pendingHold = runnable
                            holdHandler.postDelayed(runnable, AI_NOTE_HOLD_MS)
                        } else {
                            val noteId = viewModel.aiNoteAt(pageX, pageY)
                            if (noteId != null) {
                                holdDownX = downX
                                holdDownY = downY
                                val runnable = Runnable {
                                    pendingHold = null
                                    holdConsumed = true
                                    viewModel.selectAiNote(noteId)
                                }
                                pendingHold = runnable
                                holdHandler.postDelayed(runnable, AI_NOTE_HOLD_MS)
                            }
                        }
                    }
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    // Pinch zoom (FA-20): scale about the finger centroid + pan by its movement.
                    if (pinching) {
                        val span = fingerSpan(event)
                        if (span.count >= 2 && span.spread > 0f && pinchPrevSpread > 0f) {
                            viewModel.zoomAndPan(
                                focalX = span.cx,
                                focalY = span.cy,
                                scaleFactor = span.spread / pinchPrevSpread,
                                panDx = span.cx - pinchPrevCx,
                                panDy = span.cy - pinchPrevCy,
                            )
                            pinchPrevSpread = span.spread
                            pinchPrevCx = span.cx
                            pinchPrevCy = span.cy
                        }
                        return@OnTouchListener true
                    }
                    // A lone finger drag scrolls or swipes to turn pages (FA-20), before stylus
                    // handling. The axis locks after a small slop.
                    scrollPointerId?.let { sid ->
                        // A fired link hold owns the rest of this gesture — don't also scroll.
                        if (scrollHoldConsumed) return@OnTouchListener true
                        val si = event.findPointerIndex(sid)
                        if (si >= 0) {
                            val fx = event.getX(si)
                            val fy = event.getY(si)
                            val totalDx = fx - scrollStartX
                            val totalDy = fy - scrollStartY
                            // Real movement means a scroll, not a hold — cancel the pending
                            // link hold (FA-24 device feedback).
                            if (pendingScrollHold != null && hypot(totalDx, totalDy) > HOLD_MOVE_SLOP_PX) {
                                cancelPendingScrollHold()
                            }
                            if (scrollAxis == 0) {
                                val locked = lockAxisOrUndecided(totalDx, totalDy)
                                if (locked != 0) {
                                    scrollAxis = locked
                                    lastScrollY = fy // start scroll/swipe from the lock point
                                    lastScrollX = fx
                                    // When zoomed wider than the screen a horizontal drag PANS; only a
                                    // fit-width page turns (horizontal mode) — captured for release.
                                    horizontalIsPan = viewModel.canPanHorizontally()
                                }
                            }
                            if (scrollAxis == 1) {
                                viewModel.scrollBy(fy - lastScrollY)
                                lastScrollY = fy
                            } else if (scrollAxis == 2) {
                                val dx = fx - lastScrollX
                                if (horizontalIsPan) {
                                    viewModel.panBy(dx, 0f)
                                } else {
                                    // Live page-turn slide: the page follows the finger; release decides
                                    // whether it turns or springs back (FA-20).
                                    viewModel.swipeBy(dx)
                                }
                                lastScrollX = fx
                            }
                        }
                        return@OnTouchListener true
                    }
                    // Only the tracked (stylus) pointer advances the stroke; finger pointers in
                    // the same event are ignored. Keep consuming so the gesture stays alive.
                    val pointerId = currentPointerId ?: return@OnTouchListener true
                    val pointerIndex = event.findPointerIndex(pointerId)
                    if (pointerIndex < 0) return@OnTouchListener true
                    // FA-21: movement cancels a pending hold (it's a stroke → write-over the box);
                    // once a hold HAS fired, stop advancing the stroke — it's cancelled on up.
                    if (pendingHold != null &&
                        hypot(event.getX(pointerIndex) - holdDownX, event.getY(pointerIndex) - holdDownY) >
                        HOLD_MOVE_SLOP_PX
                    ) {
                        cancelPendingHold()
                        linkTapEligible = false // a real stroke over the link — write-over, not a tap
                    }
                    if (holdConsumed) return@OnTouchListener true
                    // FA-23 hold-to-straighten. Once snapped, MOVEs only adjust the line endpoint;
                    // before that, any travel past the slop re-arms the stationary timer, and the
                    // timer is only armed at all once the stroke is deliberately long (so taps,
                    // short marks and the FA-21 stationary AI-box hold can never convert).
                    if (!erasing) {
                        val mx = event.getX(pointerIndex)
                        val my = event.getY(pointerIndex)
                        if (straightening) {
                            val pt = viewModel.pageTransform.value
                            viewModel.updateStraightLine(pt.screenToPageX(mx), pt.screenToPageY(my))
                            return@OnTouchListener true
                        }
                        lastMoveScreenX = mx
                        lastMoveScreenY = my
                        if (hypot(mx - straightenAnchorX, my - straightenAnchorY) > STRAIGHTEN_SLOP_PX) {
                            straightenAnchorX = mx
                            straightenAnchorY = my
                            cancelStraightenTimer()
                            if (hypot(mx - strokeStartScreenX, my - strokeStartScreenY) >
                                STRAIGHTEN_MIN_LENGTH_PX
                            ) {
                                val runnable = Runnable {
                                    straightenRunnable = null
                                    straightening = true
                                    // The drawn ink vanishes; the VM preview line takes over.
                                    currentStrokeId?.let { inProgressStrokesView.cancelStroke(it) }
                                    currentStrokeId = null
                                    if (patternActive) {
                                        viewModel.cancelPatternStroke()
                                        patternActive = false
                                    }
                                    val pt = viewModel.pageTransform.value
                                    viewModel.beginStraightLine(
                                        pt.screenToPageX(strokeStartScreenX),
                                        pt.screenToPageY(strokeStartScreenY),
                                        pt.screenToPageX(lastMoveScreenX),
                                        pt.screenToPageY(lastMoveScreenY),
                                    )
                                }
                                straightenRunnable = runnable
                                holdHandler.postDelayed(runnable, STRAIGHTEN_HOLD_MS)
                            }
                        }
                    }
                    if (erasing) {
                        viewModel.eraseAt(event.getX(pointerIndex), event.getY(pointerIndex))
                    } else if (patternActive) {
                        // Feed the batched (historical) points too, so fast strokes stay smooth.
                        val pt = viewModel.pageTransform.value
                        for (h in 0 until event.historySize) {
                            viewModel.addPatternPoint(
                                pt.screenToPageX(event.getHistoricalX(pointerIndex, h)),
                                pt.screenToPageY(event.getHistoricalY(pointerIndex, h)),
                                event.getHistoricalEventTime(h) - patternStartTime,
                                event.getHistoricalPressure(pointerIndex, h),
                            )
                        }
                        viewModel.addPatternPoint(
                            pt.screenToPageX(event.getX(pointerIndex)),
                            pt.screenToPageY(event.getY(pointerIndex)),
                            event.eventTime - patternStartTime,
                            event.getPressure(pointerIndex),
                        )
                    } else {
                        val strokeId = currentStrokeId ?: return@OnTouchListener true
                        inProgressStrokesView.addToStroke(event, pointerId, strokeId, predictedEvent)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    // End a pinch once fewer than two fingers remain (FA-20).
                    if (pinching) {
                        val exclude = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
                        val remaining = fingerSpan(event, excludeIndex = exclude)
                        if (remaining.count >= 2) {
                            pinchPrevSpread = remaining.spread
                            pinchPrevCx = remaining.cx
                            pinchPrevCy = remaining.cy
                        } else {
                            pinching = false
                            viewModel.endPinch()
                            // Don't let a lingering finger start a scroll (avoid a jump); it will on a
                            // fresh DOWN.
                            scrollPointerId = null
                            scrollAxis = 0
                        }
                        return@OnTouchListener true
                    }
                    // Only finish when the tracked pointer lifts; a finger lifting is ignored.
                    val pointerId = event.getPointerId(event.actionIndex)
                    if (pointerId == scrollPointerId) {
                        cancelPendingScrollHold()
                        // Resolve a horizontal swipe (turn or spring back) or a vertical drag (elastic
                        // page-turn or spring back in vertical mode). A pan just ends. (FA-20)
                        if (scrollAxis == 2 && !horizontalIsPan) viewModel.releaseSwipe()
                        if (scrollAxis == 1) viewModel.releaseScroll()
                        // A clean tap (no axis ever locked, no hold fired): open a tapped link box,
                        // else select the tapped AI box, else deselect. A fired hold (link selected /
                        // broken menu shown) consumes the gesture — the UP does nothing more.
                        if (scrollAxis == 0 && !scrollHoldConsumed) {
                            val tappedLink = scrollDownLinkId
                            val tapped = scrollDownNoteId
                            when {
                                tappedLink != null -> viewModel.tapLink(tappedLink)
                                tapped != null -> viewModel.selectAiNote(tapped)
                                else -> viewModel.clearSelection()
                            }
                        }
                        scrollPointerId = null
                        scrollAxis = 0
                        scrollDownNoteId = null
                        scrollDownLinkId = null
                        scrollHoldConsumed = false
                    }
                    if (pointerId == currentPointerId) {
                        cancelPendingHold()
                        cancelStraightenTimer()
                        if (straightening) {
                            viewModel.commitStraightLine()
                            straightening = false
                        }
                        // FA-24: a clean pen UP on a link box (no hold fired, no movement past the
                        // slop) is a TAP — cancel the nascent stroke (no dot) and open the target.
                        val wasLinkTap = downLinkId != null && !holdConsumed && linkTapEligible
                        currentStrokeId?.let { strokeId ->
                            // A fired hold-to-select (or a link tap) cancels the stroke so it leaves
                            // no dot (FA-21/24).
                            if (holdConsumed || wasLinkTap) {
                                inProgressStrokesView.cancelStroke(strokeId, event)
                            } else {
                                inProgressStrokesView.finishStroke(event, pointerId, strokeId)
                            }
                        }
                        if (patternActive) {
                            if (holdConsumed || wasLinkTap) {
                                viewModel.cancelPatternStroke()
                            } else {
                                viewModel.finishPatternStroke()
                            }
                            patternActive = false
                        }
                        if (wasLinkTap) downLinkId?.let { viewModel.tapLink(it) }
                        downLinkId = null
                        linkTapEligible = false
                        holdConsumed = false
                        currentPointerId = null
                        currentStrokeId = null
                        currentIsFinger = false
                        erasing = false
                        view.performClick()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelPendingHold()
                    cancelStraightenTimer()
                    if (straightening) {
                        viewModel.cancelStraightLine()
                        straightening = false
                    }
                    holdConsumed = false
                    downLinkId = null
                    linkTapEligible = false
                    currentStrokeId?.let { inProgressStrokesView.cancelStroke(it, event) }
                    if (patternActive) {
                        viewModel.cancelPatternStroke()
                        patternActive = false
                    }
                    currentPointerId = null
                    currentStrokeId = null
                    currentIsFinger = false
                    erasing = false
                    if (pinching) { pinching = false; viewModel.endPinch() }
                    if (scrollAxis == 2 && !horizontalIsPan) viewModel.releaseSwipe() // spring an in-progress swipe back
                    if (scrollAxis == 1) viewModel.releaseScroll() // spring an in-progress vertical overscroll back
                    cancelPendingScrollHold()
                    scrollHoldConsumed = false
                    scrollPointerId = null
                    scrollAxis = 0
                    scrollDownNoteId = null
                    scrollDownLinkId = null
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

