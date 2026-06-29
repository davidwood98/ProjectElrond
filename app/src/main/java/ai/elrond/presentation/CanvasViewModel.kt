package ai.elrond.presentation

import ai.elrond.domain.LiveTransform
import ai.elrond.data.ThumbnailRenderer
import ai.elrond.data.ThumbnailCache
import ai.elrond.domain.StrokeTransforms
import ai.elrond.domain.StrokeSelection
import ai.elrond.domain.SelectionState
import ai.elrond.domain.SelectionBounds
import ai.elrond.domain.ClipboardState
import ai.elrond.domain.CanvasTool
import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.FingerGesture
import ai.elrond.domain.FingerGestureAction
import ai.elrond.domain.StylusHoldTool
import ai.elrond.BuildConfig
import ai.elrond.domain.AiInkNote
import ai.elrond.domain.GestureTriggerDetector
import ai.elrond.data.HandwritingRecognizer
import ai.elrond.data.MlKitHandwritingRecognizer
import ai.elrond.domain.NotePosition
import ai.elrond.domain.PrefixTriggerState
import ai.elrond.domain.QueryTriggerDetector
import ai.elrond.domain.TriggerMode
import ai.elrond.domain.UnitSystem
import ai.elrond.domain.Notebook
import ai.elrond.domain.NotePage
import ai.elrond.domain.PageLayer
import ai.elrond.domain.PageNavigationMode
import ai.elrond.domain.PageTransform
import ai.elrond.domain.PageViewOrientation
import ai.elrond.domain.PaperColor
import ai.elrond.domain.PaperStyle
import ai.elrond.domain.defaultAiNotePosition
import ai.elrond.domain.groupStrokesIntoLines
import ai.elrond.domain.notebookTitle
import ai.elrond.domain.selectQuestionLines
import ai.elrond.domain.strokeCentroid
import ai.elrond.domain.strokeLoopOrNull
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AiTaskExtractor
import ai.elrond.aibackend.AssistantCapabilities
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.aibackend.anthropic.AnthropicConfig
import ai.elrond.aibackend.anthropic.AnthropicProvider
import ai.elrond.data.CalendarEvent
import ai.elrond.data.CalendarRepository
import ai.elrond.data.NoteRepository
import ai.elrond.data.SessionNotesTracker
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import ai.elrond.data.ExtractionScheduler
import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.SuggestionType
import ai.elrond.data.SettingsRepository
import ai.elrond.domain.PendingTaskExtraction
import ai.elrond.domain.TodoPriority
import android.content.Context
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import androidx.lifecycle.SavedStateHandle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Holds canvas drawing state: finished (dry) strokes, the active tool, brush settings,
 * stroke undo/redo history, and AI response notes.
 *
 * Wet (in-progress) ink is owned by [androidx.ink.authoring.InProgressStrokesView] for
 * low-latency front-buffer rendering; strokes are handed to this ViewModel once finished.
 *
 * AI assistant: after each finished stroke (debounced), strokes are grouped into
 * handwriting lines and only the line containing the most recent stroke is recognized.
 * If it ends with `/Q`, the question is the text on that line — or the line directly
 * above when `/Q` stands alone — and the remaining lines are sent along as page
 * context. The response is placed on the canvas as a handwriting-style [AiInkNote].
 */
@HiltViewModel
class CanvasViewModel(
    private val recognizer: HandwritingRecognizer? = null,
    private val aiProvider: AIProvider? = null,
    private val lineSplitter: (List<Stroke>) -> List<List<Stroke>> = ::groupStrokesIntoLines,
    private val notePlacer: (List<Stroke>) -> NotePosition = ::defaultAiNotePosition,
    private val repository: NoteRepository? = null,
    private val taskExtractor: TaskExtractor? = null,
    private val todoRepository: TodoRepository? = null,
    private val calendarRepository: CalendarRepository? = null,
    private val suggestionRepository: SuggestionRepository? = null,
    private val pageId: String? = null,
    /** Enqueues background auto-extraction for [pageId] after a save (null in tests / when off). */
    private val enqueueExtraction: ((pageId: String) -> Unit)? = null,
    triggerCommandFlow: Flow<String>? = null,
    /** Picks which lines above a bare trigger form a multi-line question (default: span-based). */
    private val questionLineSelector: (List<List<Stroke>>, Int) -> List<Int> = ::selectQuestionLines,
    /** Returns a stroke's lasso polygon (gesture mode) or null when it isn't a loop. */
    private val lassoOf: (Stroke) -> List<GestureTriggerDetector.Point>? = ::strokeLoopOrNull,
    /** Centroid of a stroke, used to test lasso enclosure (gesture mode + lasso tool). */
    private val centroidOf: (Stroke) -> GestureTriggerDetector.Point = ::strokeCentroid,
    /** Bakes a move/scale into a stroke (lasso tool). Injected so JVM tests use a fake. */
    private val strokeTransformer: (Stroke, LiveTransform) -> Stroke = StrokeTransforms::transformStroke,
    /** Axis-aligned bounds of a stroke (lasso tool). Injected so JVM tests use a fake. */
    private val strokeBoundsOf: (Stroke) -> SelectionBounds = StrokeTransforms::strokeBounds,
    triggerModeFlow: Flow<TriggerMode>? = null,
    /** Prefix-mode inactivity delay (ms) before the question fires; null keeps the default. */
    prefixTriggerDelayMsFlow: Flow<Long>? = null,
    /** Prefix-mode no-prompt timeout (ms) before an abandoned command reverts; null keeps default. */
    prefixNoPromptTimeoutMsFlow: Flow<Long>? = null,
    stylusOnlyFlow: Flow<Boolean>? = null,
    /** Global default paper style — used when this notebook has no per-notebook override (FA-20). */
    globalPaperStyleFlow: Flow<PaperStyle>? = null,
    /** Global default scroll direction — used when this notebook has no per-notebook override (FA-20). */
    globalPageNavigationModeFlow: Flow<PageNavigationMode>? = null,
    /** Unit system the AI must use for measurements in answers; null keeps the default (Metric). */
    unitSystemFlow: Flow<UnitSystem>? = null,
    /** Lasso-move snap-back threshold (fraction of canvas size); null keeps the default. */
    lassoSnapBackThresholdFlow: Flow<Float>? = null,
    /** Lasso-move snap-back on/off; null keeps the default (on). */
    lassoSnapBackEnabledFlow: Flow<Boolean>? = null,
    /** Finger-gestures master switch (FA-19); null keeps the default (on). */
    fingerGesturesEnabledFlow: Flow<Boolean>? = null,
    /** Action bound to a single two-finger tap; null keeps the default (Undo). */
    twoFingerTapActionFlow: Flow<FingerGestureAction>? = null,
    /** Action bound to a single three-finger tap; null keeps the default (Redo). */
    threeFingerTapActionFlow: Flow<FingerGestureAction>? = null,
    /** Action bound to a double two-finger tap; null keeps the default (last-tool swap). */
    twoFingerDoubleTapActionFlow: Flow<FingerGestureAction>? = null,
    /** Action bound to a double three-finger tap; null keeps the default (none). */
    threeFingerDoubleTapActionFlow: Flow<FingerGestureAction>? = null,
    /** S Pen button master switch (FA-19); null keeps the default (on). */
    stylusButtonEnabledFlow: Flow<Boolean>? = null,
    /** Tool the S Pen button springs to while held; null keeps the default (Eraser). */
    stylusHoldToolFlow: Flow<StylusHoldTool>? = null,
    /** Action bound to a double S Pen button click; null keeps the default (select Lasso). */
    stylusDoubleClickActionFlow: Flow<FingerGestureAction>? = null,
    /** Action bound to a single S Pen button click; null keeps the default (none). */
    stylusSingleClickActionFlow: Flow<FingerGestureAction>? = null,
    /** Write-through for the palm-rejection preference (null in tests). */
    private val persistStylusOnly: (suspend (Boolean) -> Unit)? = null,
    /**
     * Renders + caches this page's note-card thumbnail (load preview → render → write). Injected as
     * a seam so JVM tests assert the orchestration with a fake; null in tests / when no page is set.
     */
    private val thumbnailGenerator: (suspend (pageId: String) -> Unit)? = null,
    /** Records this page as opened this session (FA-16) — feeds the editor's session note tabs. */
    private val sessionNotesTracker: SessionNotesTracker? = null,
    private val triggerDebounceMillis: Long = TRIGGER_DEBOUNCE_MILLIS,
    private val autoSaveDebounceMillis: Long = AUTOSAVE_DEBOUNCE_MILLIS,
    private val requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : ViewModel() {

    /**
     * Hilt entry point — production dependencies only. The full constructor above keeps its
     * test-only knobs (line splitter, note placer, debounce/timeout) at their defaults, so the
     * unit tests construct it directly without going through Hilt. [pageId] comes from the nav
     * back-stack [SavedStateHandle].
     */
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        recognizer: HandwritingRecognizer,
        aiProvider: AIProvider?,
        repository: NoteRepository,
        taskExtractor: TaskExtractor?,
        todoRepository: TodoRepository,
        calendarRepository: CalendarRepository,
        suggestionRepository: SuggestionRepository,
        enqueueExtraction: @JvmSuppressWildcards (String) -> Unit,
        settings: SettingsRepository,
        thumbnailCache: ThumbnailCache,
        sessionNotesTracker: SessionNotesTracker,
    ) : this(
        recognizer = recognizer,
        aiProvider = aiProvider,
        repository = repository,
        taskExtractor = taskExtractor,
        todoRepository = todoRepository,
        calendarRepository = calendarRepository,
        suggestionRepository = suggestionRepository,
        pageId = savedStateHandle.get<String>("pageId"),
        enqueueExtraction = enqueueExtraction,
        triggerCommandFlow = settings.triggerCommand,
        triggerModeFlow = settings.triggerMode,
        prefixTriggerDelayMsFlow = settings.prefixTriggerDelayMs,
        prefixNoPromptTimeoutMsFlow = settings.prefixNoPromptTimeoutMs,
        stylusOnlyFlow = settings.stylusOnly,
        globalPaperStyleFlow = settings.paperStyle,
        globalPageNavigationModeFlow = settings.pageNavigationMode,
        unitSystemFlow = settings.unitSystem,
        lassoSnapBackThresholdFlow = settings.lassoSnapBackThreshold,
        lassoSnapBackEnabledFlow = settings.lassoSnapBackEnabled,
        fingerGesturesEnabledFlow = settings.fingerGesturesEnabled,
        twoFingerTapActionFlow = settings.twoFingerTapAction,
        threeFingerTapActionFlow = settings.threeFingerTapAction,
        twoFingerDoubleTapActionFlow = settings.twoFingerDoubleTapAction,
        threeFingerDoubleTapActionFlow = settings.threeFingerDoubleTapAction,
        stylusButtonEnabledFlow = settings.stylusButtonEnabled,
        stylusHoldToolFlow = settings.stylusHoldTool,
        stylusDoubleClickActionFlow = settings.stylusDoubleClickAction,
        stylusSingleClickActionFlow = settings.stylusSingleClickAction,
        persistStylusOnly = { settings.setStylusOnly(it) },
        // Real generator: render the page's normalized polylines (the same data the card draws) to a
        // bitmap off-thread and cache it; an empty page drops any stale file so the card shows blank.
        thumbnailGenerator = { pid ->
            val polylines = repository.loadStrokePreview(pid)
            if (polylines.isEmpty()) {
                thumbnailCache.delete(pid)
            } else {
                val bitmap = withContext(Dispatchers.Default) { ThumbnailRenderer.render(polylines) }
                thumbnailCache.write(pid, bitmap)
                bitmap.recycle()
            }
        },
        sessionNotesTracker = sessionNotesTracker,
    )

    private val _finishedStrokes = MutableStateFlow<List<CanvasStroke>>(emptyList())
    val finishedStrokes: StateFlow<List<CanvasStroke>> = _finishedStrokes.asStateFlow()

    /** Lasso selection (FA-9): null when nothing is selected. UI-only, never persisted. */
    private val _selection = MutableStateFlow<SelectionState?>(null)
    val selection: StateFlow<SelectionState?> = _selection.asStateFlow()

    /** Clipboard banner state (FA-9): drives the bottom bar + arms tap-to-paste. */
    private val _clipboard = MutableStateFlow(ClipboardState.EMPTY)
    val clipboard: StateFlow<ClipboardState> = _clipboard.asStateFlow()

    /** Strokes held for paste (deep copies captured at copy/cut, with their group structure). */
    private var clipboardStrokes: List<CanvasStroke> = emptyList()
    /** AI response boxes held for paste alongside [clipboardStrokes] (FA-21). */
    private var clipboardAiNotes: List<AiInkNote> = emptyList()
    private var clipboardBounds: SelectionBounds? = null

    /**
     * Last measured on-screen size of each AI note, in page-space px (width, height), reported by the
     * view (FA-21). The wrap-to-content height isn't known to the VM otherwise, so this drives the
     * selection bounds (the box that hugs an AI note) and lasso/hold hit-testing.
     */
    private val aiNoteMeasured = mutableMapOf<String, Pair<Float, Float>>()

    private val _tool = MutableStateFlow(CanvasTool.PEN)
    val tool: StateFlow<CanvasTool> = _tool.asStateFlow()

    /** Palm rejection: when true (default), finger touches never draw ink. */
    private val _stylusOnly = MutableStateFlow(true)
    val stylusOnly: StateFlow<Boolean> = _stylusOnly.asStateFlow()

    /** The tool selected before the current one — restored by the FA-19 "last tool swap" gesture. */
    private var previousTool: CanvasTool = CanvasTool.PEN

    // ── Finger gestures (FA-19) ───────────────────────────────────────────────────────────────
    // Multi-finger taps bound to canvas actions, kept in sync with settings. [fingerGesturesEnabled]
    // is read by InkCanvas to gate detection; the action flows are read via [isDoubleTapBound] /
    // [onFingerGesture]. Defaults match SettingsRepository so JVM tests (no flows) are deterministic.

    private val _fingerGesturesEnabled = MutableStateFlow(true)
    val fingerGesturesEnabled: StateFlow<Boolean> = _fingerGesturesEnabled.asStateFlow()

    private val _twoFingerTapAction = MutableStateFlow(FingerGestureAction.UNDO)
    private val _threeFingerTapAction = MutableStateFlow(FingerGestureAction.REDO)
    private val _twoFingerDoubleTapAction = MutableStateFlow(FingerGestureAction.LAST_TOOL_SWAP)
    private val _threeFingerDoubleTapAction = MutableStateFlow(FingerGestureAction.NONE)

    // ── S Pen button (FA-19) ──────────────────────────────────────────────────────────────────
    // [stylusButtonEnabled] gates detection in InkCanvas; click actions are read via
    // [isStylusDoubleClickBound] / [onStylusClick]; the momentary hold uses [stylusHoldTool].

    private val _stylusButtonEnabled = MutableStateFlow(true)
    val stylusButtonEnabled: StateFlow<Boolean> = _stylusButtonEnabled.asStateFlow()

    private val _stylusHoldTool = MutableStateFlow(StylusHoldTool.ERASER)
    private val _stylusDoubleClickAction = MutableStateFlow(FingerGestureAction.SELECT_LASSO)
    private val _stylusSingleClickAction = MutableStateFlow(FingerGestureAction.NONE)

    /** Tool active before a momentary button-hold began, restored on release; null when not held. */
    private var toolBeforeHold: CanvasTool? = null

    private val _aiState = MutableStateFlow<AiUiState>(AiUiState.Idle)
    val aiState: StateFlow<AiUiState> = _aiState.asStateFlow()

    /** AI responses rendered on the canvas as handwriting-style notes. */
    private val _aiNotes = MutableStateFlow<List<AiInkNote>>(emptyList())
    val aiNotes: StateFlow<List<AiInkNote>> = _aiNotes.asStateFlow()

    /**
     * Emits the id of an AI note the instant it's created via `/Q`. The UI uses this to
     * auto-select freshly created notes (when enabled) WITHOUT also selecting notes that
     * were merely loaded from storage — those only ever flow through [aiNotes].
     */
    private val _createdNoteEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val createdNoteEvents: SharedFlow<String> = _createdNoteEvents.asSharedFlow()

    /** The open page's notebook id (set once the page loads); drives the multi-page pager (FA-20). */
    private var notebookId: String? = null

    /** The notebook's pages ordered by page number — backs the page indicator + page turns (FA-20). */
    private val _notebookPages = MutableStateFlow<List<NotePage>>(emptyList())
    val notebookPages: StateFlow<List<NotePage>> = _notebookPages.asStateFlow()

    /**
     * The single rendered page layer — the open page (FA-20). Kept as a list so the ink view's render
     * path is unchanged; continuous multi-page is a future feature.
     */
    private val _pageLayers = MutableStateFlow<List<PageLayer>>(emptyList())
    val pageLayers: StateFlow<List<PageLayer>> = _pageLayers.asStateFlow()

    /** The page open in this editor (its id), for the page indicator. */
    val currentPageId: String? get() = pageId

    /** Emits the page id to navigate to when the user swipes to turn the page (FA-20). */
    private val _pageTurnEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pageTurnEvents: SharedFlow<String> = _pageTurnEvents.asSharedFlow()

    /** Tasks the AI extracted from the page, awaiting the user's confirmation to save. */
    private val _pendingExtraction = MutableStateFlow<PendingTaskExtraction?>(null)
    val pendingExtraction: StateFlow<PendingTaskExtraction?> = _pendingExtraction.asStateFlow()

    /**
     * A short, self-clearing notification shown over the canvas (e.g. "Already on your to-do
     * list" when a re-triggered selection only contains items that already exist). Null when
     * nothing is showing; set by [showTransientMessage], which auto-clears it after a beat.
     */
    private val _transientMessage = MutableStateFlow<String?>(null)
    val transientMessage: StateFlow<String?> = _transientMessage.asStateFlow()
    private var transientMessageJob: Job? = null

    /** Background-extracted (FA-2) suggestions for this page awaiting a Yes/No popup decision. */
    val pendingSuggestions: StateFlow<List<PendingSuggestion>> =
        if (suggestionRepository != null && pageId != null) {
            suggestionRepository.observePending(pageId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        } else {
            MutableStateFlow<List<PendingSuggestion>>(emptyList()).asStateFlow()
        }

    // --- Undo / redo (stroke history) ---

    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Snapshot taken at eraser-gesture start so one gesture = one undo step. */
    private var eraseGestureSnapshot: List<CanvasStroke>? = null

    private var triggerDetectionJob: Job? = null

    /** Last prompt already handled — prevents re-triggering on unchanged content. */
    private var lastHandledPrompt: String? = null

    /** Strokes as last persisted — avoids redundant writes and enables the close-flush. */
    private var lastPersisted: List<CanvasStroke> = emptyList()

    /**
     * True once freehand pen ink has changed since the last extraction enqueue. Only genuinely new
     * handwriting should trigger the FA-2 background auto-extraction — lasso edits (move / scale /
     * duplicate / paste / cut / group / ungroup) rearrange or copy *existing* ink, so they must not
     * re-run the extractor (a pasted item is already covered by the same de-dup). Set in
     * [onStrokesFinished]; checked-and-cleared by the autosave before enqueuing.
     */
    @Volatile
    private var contentDirtyForExtraction: Boolean = false

    /**
     * True once ink changed since the page's note-card thumbnail was last (re)generated. Set on any
     * ink mutation (a finished pen stroke; the autosave detecting a content change covers erase /
     * undo / lasso edits too); cleared by [generateThumbnail]. Drives the close-flush in
     * [onCleared] so a quick back-press still leaves a fresh thumbnail. Readable by tests.
     */
    @Volatile
    internal var thumbnailDirty: Boolean = false
        private set

    /** AI notes as last persisted — same role for the ai_notes table. */
    private var lastPersistedAiNotes: List<AiInkNote> = emptyList()

    /** Latest canvas width in px, reported by the UI; sizes new full-line AI boxes. */
    private var canvasWidthPx: Float = 0f

    /** Latest canvas height in px, reported by the UI; used to normalise the snap-back distance. */
    private var canvasHeightPx: Float = 0f

    /**
     * Page sheet width in px. The page is a **fixed portrait A-ratio sheet** whose width equals the
     * device's shorter screen edge, so it never grows or reflows when the device rotates (FA-20 B1):
     * in portrait it fills the width, in landscape it stays that same width and is centred (the
     * horizontal margin lives in [PageTransform.offsetX]). 0 until the canvas size is first reported.
     */
    private var pageWidthPx: Float = 0f

    /** Vertical scroll offset (px) within the current page; 0 = top (FA-20). */
    private var scrollPx: Float = 0f

    /**
     * Elastic page-turn overscroll (px) for VERTICAL mode (FA-20): a drag past the page's top/bottom
     * edge pulls the page (damped); release turns the page or springs back. >0 = pulled down (toward the
     * previous page), <0 = pulled up (toward the next page). Always 0 at rest.
     */
    private var overscrollY: Float = 0f

    /**
     * Pinch-zoom factor (FA-20). 1.0 = 100% = the page fills the shorter screen edge (the stored page
     * space at 1:1). It multiplies [PageTransform.scale]; everything else (capture, eraser, lasso, AI
     * notes, paper) already routes through the transform, so zoom is purely this factor + the centring/
     * pan recompute. Clamped to [[MIN_ZOOM], [MAX_ZOOM]].
     */
    private var zoomScale: Float = 1f

    /**
     * Free horizontal pan (px) used only when the zoomed page is wider than the viewport; the page
     * origin's screen x. Ignored (page re-centred) while the page fits. See [refreshPageTransform].
     */
    private var panXpx: Float = 0f

    /**
     * Transient horizontal page-turn slide (px), folded into [PageTransform.offsetX] (FA-20). During a
     * horizontal finger swipe the page follows the finger; on release it either slides off and turns
     * (commit) or springs elastically back to 0. Always 0 at rest.
     */
    private var swipeOffsetX: Float = 0f

    /** Drives the release animation (spring-back or slide-off) so a new swipe can cancel it. */
    private var swipeSettleJob: Job? = null

    /** Pinch-zoom level for the on-screen zoom pill; emits the new scale on each user zoom change. */
    private val _zoomEvents = MutableSharedFlow<Float>(extraBufferCapacity = 16)
    val zoomEvents: SharedFlow<Float> = _zoomEvents.asSharedFlow()

    /** True while the current zoom sits on a snap target (100% / fit-width) — drives the accent indicator. */
    private val _zoomSnapped = MutableStateFlow(true)
    val zoomSnapped: StateFlow<Boolean> = _zoomSnapped.asStateFlow()

    /**
     * The single source of truth mapping the page's logical coordinate space ⇄ screen pixels (FA-20):
     * a uniform scale (1 in fit-width; pinch-zoom in Phase 5 changes only this), a horizontal centring
     * [PageTransform.offsetX], and a vertical [PageTransform.offsetY] = −[scrollPx]. Strokes, AI notes,
     * lasso geometry and `/Q` hit-tests are all stored in page space; every render/capture site routes
     * through this transform (`pageToScreenX/Y`, `screenToPageX/Y`) rather than hand-threading the
     * scroll, so the centring + scroll (+ future zoom) live in one place.
     */
    private val _pageTransform = MutableStateFlow(PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f))
    val pageTransform: StateFlow<PageTransform> = _pageTransform.asStateFlow()

    /**
     * The DOCUMENT → screen transform (FA-20): like [pageTransform] but anchored to the document top
     * (page 1), not the open page. Drives the continuous multi-page dry-ink + paper rendering, which
     * place each page at `offsetY + page.docTopPx · scale`.
     */
    private val _documentTransform = MutableStateFlow(PageTransform(scale = 1f, offsetX = 0f, offsetY = 0f))
    val documentTransform: StateFlow<PageTransform> = _documentTransform.asStateFlow()

    /** Total document height in page-space px (one page in horizontal mode; all pages + gaps in vertical). */
    private val _documentHeightPx = MutableStateFlow(0f)
    val documentHeightPx: StateFlow<Float> = _documentHeightPx.asStateFlow()

    /** Page sheet width in page-space px (the fixed A-ratio sheet width); drives the paper rendering. */
    private val _pageWidthSpacePx = MutableStateFlow(0f)
    val pageWidthSpacePx: StateFlow<Float> = _pageWidthSpacePx.asStateFlow()

    /** Lasso-move snap-back threshold (fraction of canvas size), kept in sync with settings. */
    @Volatile
    private var lassoSnapBackThreshold: Float = SettingsRepository.DEFAULT_LASSO_SNAP_BACK_THRESHOLD

    /** Whether lasso-move snap-back is enabled, kept in sync with settings. */
    @Volatile
    private var lassoSnapBackEnabled: Boolean = SettingsRepository.DEFAULT_LASSO_SNAP_BACK_ENABLED

    /** Current activation command (default `/Q`), kept in sync with settings. */
    @Volatile
    private var triggerCommand: String = QueryTriggerDetector.DEFAULT_TRIGGER

    /** Current activation method (written command vs lasso gesture), kept in sync with settings. */
    @Volatile
    private var triggerMode: TriggerMode = TriggerMode.COMMAND

    // ── Prefix `/Q` activation (TriggerMode.PREFIX_COMMAND) ───────────────────────────────────

    /** Listening state for prefix-mode `/Q`; drives the bottom-of-canvas listening indicator. */
    private val _prefixTriggerState = MutableStateFlow<PrefixTriggerState>(PrefixTriggerState.Idle)
    val prefixTriggerState: StateFlow<PrefixTriggerState> = _prefixTriggerState.asStateFlow()

    /** Restarted on each prompt stroke; fires the query once the user pauses. */
    private var prefixInactivityJob: Job? = null

    /** Started when listening begins; cancels the session if no prompt is written in time. */
    private var prefixNoPromptJob: Job? = null

    /**
     * Stroke ids of `/Q` lines already used to start a listening session, so a detected trigger
     * that stays on the canvas (fired or abandoned) is never re-detected on a later stroke (the
     * scan re-reads all lines, unlike the last-line-only COMMAND path).
     */
    private val consumedPrefixTriggerIds = mutableSetOf<String>()

    /** Prefix-mode inactivity delay (ms), kept in sync with settings. */
    @Volatile
    private var prefixTriggerDelayMs: Long = SettingsRepository.DEFAULT_PREFIX_TRIGGER_DELAY_MS

    /** Prefix-mode no-prompt timeout (ms), kept in sync with settings. */
    @Volatile
    private var prefixNoPromptTimeoutMs: Long = SettingsRepository.DEFAULT_PREFIX_NO_PROMPT_TIMEOUT_MS

    /** Unit system the AI must use for measurements, kept in sync with settings. */
    @Volatile
    private var unitSystem: UnitSystem = UnitSystem.DEFAULT

    init {
        globalPaperStyleFlow?.let { flow ->
            viewModelScope.launch { flow.collect { globalPaperStyle = it; refreshPageStyle() } }
        }
        globalPageNavigationModeFlow?.let { flow ->
            viewModelScope.launch { flow.collect { globalPageNavigationMode = it; refreshPageStyle() } }
        }
        triggerCommandFlow?.let { flow ->
            viewModelScope.launch { flow.collect { triggerCommand = it } }
        }
        triggerModeFlow?.let { flow ->
            viewModelScope.launch { flow.collect { triggerMode = it } }
        }
        prefixTriggerDelayMsFlow?.let { flow ->
            viewModelScope.launch { flow.collect { prefixTriggerDelayMs = it } }
        }
        prefixNoPromptTimeoutMsFlow?.let { flow ->
            viewModelScope.launch { flow.collect { prefixNoPromptTimeoutMs = it } }
        }
        stylusOnlyFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _stylusOnly.value = it } }
        }
        unitSystemFlow?.let { flow ->
            viewModelScope.launch { flow.collect { unitSystem = it } }
        }
        lassoSnapBackThresholdFlow?.let { flow ->
            viewModelScope.launch { flow.collect { lassoSnapBackThreshold = it } }
        }
        lassoSnapBackEnabledFlow?.let { flow ->
            viewModelScope.launch { flow.collect { lassoSnapBackEnabled = it } }
        }
        fingerGesturesEnabledFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _fingerGesturesEnabled.value = it } }
        }
        twoFingerTapActionFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _twoFingerTapAction.value = it } }
        }
        threeFingerTapActionFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _threeFingerTapAction.value = it } }
        }
        twoFingerDoubleTapActionFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _twoFingerDoubleTapAction.value = it } }
        }
        threeFingerDoubleTapActionFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _threeFingerDoubleTapAction.value = it } }
        }
        stylusButtonEnabledFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _stylusButtonEnabled.value = it } }
        }
        stylusHoldToolFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _stylusHoldTool.value = it } }
        }
        stylusDoubleClickActionFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _stylusDoubleClickAction.value = it } }
        }
        stylusSingleClickActionFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _stylusSingleClickAction.value = it } }
        }
        // Pre-download the handwriting model so the first /Q is fast.
        recognizer?.let { viewModelScope.launch { it.warmUp() } }

        if (repository != null && pageId != null) {
            viewModelScope.launch {
                runCatching { repository.loadStrokes(pageId) }
                    .onSuccess { loaded ->
                        if (loaded.isNotEmpty()) _finishedStrokes.value = loaded
                    }
                runCatching { repository.loadAiNotes(pageId) }
                    .onSuccess { loaded -> _aiNotes.value = loaded }
                runCatching { repository.getPage(pageId) }.getOrNull()?.let { page ->
                    notebookId = page.notebookId
                    // Track the notebook's ordered pages for the page indicator + swipe page-turns.
                    // The date comes from the cover (page 1); the TITLE is a notebook property
                    // (see refreshTitle) so it survives page reorders (FA-20).
                    viewModelScope.launch {
                        repository.observePagesOrdered(page.notebookId).collect { pages ->
                            _notebookPages.value = pages
                            coverPage = pages.minByOrNull { it.pageNumber } ?: page
                            _pageDateLabel.value = formatPageDate((coverPage ?: page).createdAt)
                            refreshTitle()
                        }
                    }
                    // Keep the open page's layer in sync as its strokes change (draw / erase / undo).
                    viewModelScope.launch {
                        _finishedStrokes.collect { rebuildPageLayers() }
                    }
                    // Track the notebook's per-notebook page-style overrides + its name (FA-20).
                    viewModelScope.launch {
                        repository.observeNotebook(page.notebookId).collect { nb ->
                            currentNotebook = nb
                            refreshPageStyle()
                            refreshTitle()
                        }
                    }
                }
                // Record the open so this note rises to the top of "Recent" / the note tabs (FA-15).
                runCatching { repository.markOpened(pageId) }
                // Also record it in this foreground session — the editor tabs show session notes (FA-16).
                sessionNotesTracker?.recordOpened(pageId)
                lastPersisted = _finishedStrokes.value
                lastPersistedAiNotes = _aiNotes.value.filterNot { it.isError }
                startAutoSave(repository, pageId)
            }
        }
    }

    // ── Note title + date (FA-14 editor header) ──────────────────────────────────────────────
    private val _pageTitle = MutableStateFlow("Note")
    /** The open note's display title (custom name or timestamp), shown in the editor header. */
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    private val _pageDateLabel = MutableStateFlow("")
    /** The open note's creation date, e.g. "8 Feb 2026", shown beside the title. */
    val pageDateLabel: StateFlow<String> = _pageDateLabel.asStateFlow()

    /** The notebook's cover (page 1) — supplies the date + the title's timestamp fallback. */
    private var coverPage: NotePage? = null

    /**
     * Recomputes the editor title from the notebook name (the rename target) with the cover page's
     * timestamp as the fallback — a notebook-level title, independent of page order (FA-20). Called
     * whenever the notebook or its pages change.
     */
    private fun refreshTitle() {
        val cover = coverPage ?: return
        _pageTitle.value = notebookTitle(currentNotebook?.name.orEmpty(), cover)
    }

    // ── Per-notebook page style (FA-20: paper style / grid density / colour / orientation) ───────
    /** Global default paper style, kept current; used when this notebook has no override. */
    @Volatile
    private var globalPaperStyle: PaperStyle = PaperStyle.DEFAULT

    /** Global default scroll direction, kept current; used when this notebook has no override. */
    @Volatile
    private var globalPageNavigationMode: PageNavigationMode = PageNavigationMode.DEFAULT

    /** The current notebook (its page-style overrides), or null until loaded. */
    private var currentNotebook: Notebook? = null

    private val _paperStyle = MutableStateFlow(PaperStyle.DEFAULT)
    /** Effective paper style for this notebook (per-notebook override else the global default). */
    val paperStyle: StateFlow<PaperStyle> = _paperStyle.asStateFlow()

    private val _gridSpacing = MutableStateFlow(PaperStyle.DEFAULT_GRID_SPACING)
    /** Effective line/dot/grid spacing density (1–10) for this notebook. */
    val gridSpacing: StateFlow<Int> = _gridSpacing.asStateFlow()

    private val _paperColor = MutableStateFlow(PaperColor.DEFAULT)
    /** Effective paper tint for this notebook. */
    val paperColor: StateFlow<PaperColor> = _paperColor.asStateFlow()

    private val _viewOrientation = MutableStateFlow(PageViewOrientation.DEFAULT)
    /** This notebook's page orientation (PORTRAIT default / LANDSCAPE); drives the page geometry. */
    val viewOrientation: StateFlow<PageViewOrientation> = _viewOrientation.asStateFlow()

    private val _pageNavigationMode = MutableStateFlow(PageNavigationMode.DEFAULT)
    /**
     * Effective scroll direction for this notebook (per-notebook override else the global default).
     * VERTICAL = continuous multi-page scroll; HORIZONTAL = discrete swipe-to-turn pages (FA-20).
     */
    val pageNavigationMode: StateFlow<PageNavigationMode> = _pageNavigationMode.asStateFlow()

    /** Re-resolves the effective page style from the loaded notebook + the global default. */
    private fun refreshPageStyle() {
        val nb = currentNotebook
        _paperStyle.value = nb?.paperStyle ?: globalPaperStyle
        _gridSpacing.value = (nb?.gridSpacing ?: PaperStyle.DEFAULT_GRID_SPACING)
            .coerceIn(PaperStyle.MIN_GRID_SPACING, PaperStyle.MAX_GRID_SPACING)
        _paperColor.value = nb?.paperColor ?: PaperColor.DEFAULT
        val newMode = nb?.pageNavigationMode ?: globalPageNavigationMode
        val modeChanged = newMode != _pageNavigationMode.value
        _pageNavigationMode.value = newMode
        val orientation = nb?.viewOrientation ?: PageViewOrientation.DEFAULT
        if (orientation != _viewOrientation.value) {
            _viewOrientation.value = orientation
            // Orientation changes the page aspect → recompute the page size + transform.
            recomputePageSize()
            scrollBy(0f)
        }
        if (modeChanged) {
            overscrollY = 0f
            scrollBy(0f) // re-clamp + refresh for the new scroll/turn axis
        }
    }

    /** Sets this notebook's paper style (per-notebook override; FA-20). */
    fun setPaperStyle(style: PaperStyle) = updatePageStyle(paperStyle = style)

    /** Sets this notebook's grid/line/dot spacing density (1–10). */
    fun setGridSpacing(value: Int) =
        updatePageStyle(gridSpacing = value.coerceIn(PaperStyle.MIN_GRID_SPACING, PaperStyle.MAX_GRID_SPACING))

    /** Sets this notebook's paper tint. */
    fun setPaperColor(color: PaperColor) = updatePageStyle(paperColor = color)

    /** Sets this notebook's page orientation (whole-notebook; FA-20). */
    fun setViewOrientation(orientation: PageViewOrientation) =
        updatePageStyle(viewOrientation = orientation)

    /** Sets this notebook's scroll direction (per-notebook override; FA-20). */
    fun setPageNavigationMode(mode: PageNavigationMode) =
        updatePageStyle(pageNavigationMode = mode)

    private fun updatePageStyle(
        paperStyle: PaperStyle? = null,
        gridSpacing: Int? = null,
        paperColor: PaperColor? = null,
        viewOrientation: PageViewOrientation? = null,
        pageNavigationMode: PageNavigationMode? = null,
    ) {
        val repo = repository ?: return
        val nb = notebookId ?: return
        viewModelScope.launch {
            repo.updateNotebookPageStyle(nb, paperStyle, gridSpacing, paperColor, viewOrientation, pageNavigationMode)
        }
    }

    /**
     * Renames the notebook. The title is stored on the NOTEBOOK (not a page), so it survives page
     * reorders — previously it lived on the cover page and reverted when reordering changed which
     * page was page 1 (FA-20). A blank name reverts to the cover's auto-generated timestamp. The
     * header refreshes via the observeNotebook flow.
     */
    fun renamePage(newTitle: String) {
        val repo = repository ?: return
        val nb = notebookId ?: return
        viewModelScope.launch { repo.renameNotebook(nb, newTitle) }
    }

    private fun formatPageDate(createdAt: Long): String =
        DATE_LABEL_FORMATTER.format(Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()))

    /**
     * Reports the live canvas size. Width sizes new full-line AI boxes; both dimensions normalise
     * the lasso-move snap-back distance (see [commitTransform]).
     */
    fun setCanvasSize(widthPx: Float, heightPx: Float) {
        canvasWidthPx = widthPx
        canvasHeightPx = heightPx
        recomputePageSize()
        // Re-clamp the scroll if the viewport grew (e.g. rotation) and refresh the transform.
        scrollBy(0f)
    }

    /**
     * Recomputes the page sheet's on-screen width from the canvas size + this notebook's orientation
     * (FA-20). The sheet is a fixed A-ratio rectangle whose SHORT edge = the shorter screen edge, so
     * rotating the device never reflows it and stroke sizes are preserved (scale stays 1 until zoom):
     * PORTRAIT → width = short edge (tall sheet); LANDSCAPE → width = short edge × √2 (wide sheet).
     */
    private fun recomputePageSize() {
        val shortEdge =
            if (canvasWidthPx > 0f && canvasHeightPx > 0f) minOf(canvasWidthPx, canvasHeightPx) else canvasWidthPx
        pageWidthPx = when (_viewOrientation.value) {
            PageViewOrientation.LANDSCAPE -> shortEdge * PageTransform.ASPECT_RATIO
            else -> shortEdge
        }
    }

    /**
     * On-screen height of one page sheet at 100% (its A-ratio height for the current orientation). The
     * zoom factor is applied by callers where needed; the per-page sheet stays a fixed A-ratio.
     */
    private fun pageContentHeightPx(): Float = when (_viewOrientation.value) {
        PageViewOrientation.LANDSCAPE -> pageWidthPx / PageTransform.ASPECT_RATIO
        else -> pageWidthPx * PageTransform.ASPECT_RATIO
    }

    /**
     * Screen Y of the page top at scroll 0 — i.e. the page starts BELOW the title/header band, which
     * scrolls away with the page (FA-20). Reported by the UI ([setPageTopInset]); 0 until then.
     */
    private var pageTopInsetPx: Float = 0f

    /** Reports the editor header-band bottom (px) so the page docks below it and the title scrolls off. */
    fun setPageTopInset(px: Float) {
        if (px == pageTopInsetPx) return
        pageTopInsetPx = px
        scrollBy(0f)
    }

    /**
     * Recomputes [pageTransform] from the page width × [zoomScale], the horizontal placement, the top
     * inset and scroll. The page is centred horizontally when it fits the viewport; when zoomed wider
     * than the viewport it is placed by the free horizontal pan ([panXpx]), clamped so an edge can't be
     * dragged into the page. Vertically it docks at [pageTopInsetPx] and is shifted up by the scroll.
     */
    private fun refreshPageTransform() {
        val scaledPageW = pageWidthPx * zoomScale
        val offsetX = if (pageWidthPx <= 0f || scaledPageW <= canvasWidthPx) {
            ((canvasWidthPx - scaledPageW) / 2f).coerceAtLeast(0f)
        } else {
            panXpx.coerceIn(canvasWidthPx - scaledPageW, 0f)
        }
        // One editable page docked at [pageTopInsetPx], shifted up by the in-page scroll. Vertical mode
        // adds an elastic page-turn [overscrollY] (drag past an edge); horizontal uses [swipeOffsetX].
        val transform = PageTransform(
            scale = zoomScale,
            offsetX = offsetX,
            offsetY = pageTopInsetPx - scrollPx + overscrollY,
            panX = swipeOffsetX,
        )
        _pageWidthSpacePx.value = pageWidthPx
        _documentHeightPx.value = pageContentHeightPx()
        _documentTransform.value = transform
        _pageTransform.value = transform
        rebuildPageLayers()
    }

    /** Renders the open page as the single document layer (continuous multi-page is a future feature). */
    private fun rebuildPageLayers() {
        _pageLayers.value = listOf(PageLayer(pageId ?: "", 0, 0f, _finishedStrokes.value))
    }

    /** Largest valid in-page vertical scroll (px): scroll until the page bottom reaches the viewport bottom. */
    private fun maxScrollPx(): Float =
        (pageTopInsetPx + pageContentHeightPx() * zoomScale - canvasHeightPx).coerceAtLeast(0f)

    /**
     * Scrolls the open page vertically (FA-20). In vertical mode a drag past an edge becomes a damped
     * elastic overscroll that turns the page on release (the vertical analog of the horizontal swipe);
     * horizontal mode just clamps within the page. Called with 0 to re-clamp after a size/zoom change.
     */
    fun scrollBy(dragDeltaY: Float) {
        if (pageWidthPx <= 0f) {
            refreshPageTransform()
            return
        }
        val max = maxScrollPx()
        if (_pageNavigationMode.value != PageNavigationMode.VERTICAL) {
            scrollPx = (scrollPx - dragDeltaY).coerceIn(0f, max)
            refreshPageTransform()
            return
        }
        // Reconstruct the unclamped position from the current scroll + overscroll, apply the drag, then
        // re-split into in-bounds scroll and (damped) overscroll — self-contained, no extra drag state.
        val virtual = scrollPx - overscrollY / OVERSCROLL_DAMP
        val newVirtual = virtual - dragDeltaY
        scrollPx = newVirtual.coerceIn(0f, max)
        overscrollY = -(newVirtual - scrollPx) * OVERSCROLL_DAMP
        refreshPageTransform()
    }

    /**
     * Releases a vertical drag (vertical mode): a large-enough elastic overscroll turns the page — down
     * past the bottom → next page (creating one past the last page with content), up past the top →
     * previous page — otherwise it springs back. The vertical analog of [releaseSwipe].
     */
    fun releaseScroll() {
        if (_pageNavigationMode.value != PageNavigationMode.VERTICAL || overscrollY == 0f) return
        val forward = overscrollY < 0f // pulled up past the bottom → next page
        val shouldTurn = abs(overscrollY) > PAGE_TURN_OVERSCROLL_PX
        overscrollY = 0f
        refreshPageTransform()
        if (shouldTurn) viewModelScope.launch { pageTurnTarget(forward)?.let { _pageTurnEvents.emit(it) } }
    }

    // ── Pinch zoom (FA-20) ────────────────────────────────────────────────────────────────────

    /** True when the zoomed page is wider than the viewport, so a horizontal drag should pan it. */
    fun canPanHorizontally(): Boolean = pageWidthPx > 0f && pageWidthPx * zoomScale > canvasWidthPx + 0.5f

    /**
     * Applies one frame of a pinch gesture: scales by [scaleFactor] about the focal screen point
     * ([focalX], [focalY]) and pans by ([panDx], [panDy]) (the focal's movement between frames), so the
     * page content under the fingers stays put. Clamped to [[MIN_ZOOM], [MAX_ZOOM]].
     */
    fun zoomAndPan(focalX: Float, focalY: Float, scaleFactor: Float, panDx: Float, panDy: Float) {
        if (pageWidthPx <= 0f) return
        val oldScale = zoomScale
        val newScale = (oldScale * scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val eff = if (oldScale != 0f) newScale / oldScale else 1f
        // Current page origin on screen, then pan, then scale about the focal point.
        val t = _pageTransform.value
        val pannedX = t.offsetX + panDx
        val pannedY = t.offsetY + panDy
        panXpx = focalX + (pannedX - focalX) * eff
        scrollPx = pageTopInsetPx - (focalY + (pannedY - focalY) * eff)
        zoomScale = newScale
        scrollPx = scrollPx.coerceIn(0f, maxScrollPx())
        refreshPageTransform()
        emitZoom()
    }

    /** Pans the zoomed page by a screen delta (single-finger drag when zoomed wider than the viewport). */
    fun panBy(dx: Float, dy: Float) {
        if (pageWidthPx <= 0f) return
        panXpx = _pageTransform.value.offsetX + dx
        scrollPx = (scrollPx - dy).coerceIn(0f, maxScrollPx())
        refreshPageTransform()
    }

    /**
     * Ends a pinch: if the zoom is within [ZOOM_SNAP_THRESHOLD] of a snap target (100% or fit-width)
     * it snaps exactly to it (FA-20). Always refreshes the snapped indicator.
     */
    fun endPinch() {
        val target = zoomSnapTargets().minByOrNull { abs(it - zoomScale) }
        if (target != null && abs(target - zoomScale) <= ZOOM_SNAP_THRESHOLD * target) {
            zoomScale = target
            scrollPx = scrollPx.coerceIn(0f, maxScrollPx())
            refreshPageTransform()
            _zoomEvents.tryEmit(zoomScale) // update the pill to the snapped %
        }
        _zoomSnapped.value = isOnSnapTarget()
    }

    /**
     * Zoom snap targets: 100% (page at its portrait width) and fit-width (page fills the current screen
     * width). In portrait both coincide at 1.0; in landscape fit-width is the wider √2× (FA-20).
     */
    private fun zoomSnapTargets(): List<Float> {
        if (pageWidthPx <= 0f || canvasWidthPx <= 0f) return listOf(1f)
        val fit = canvasWidthPx / pageWidthPx
        return listOf(1f, fit).map { it.coerceIn(MIN_ZOOM, MAX_ZOOM) }.distinct()
    }

    private fun isOnSnapTarget(): Boolean = zoomSnapTargets().any { abs(it - zoomScale) < ZOOM_SNAP_EPSILON }

    private fun emitZoom() {
        _zoomSnapped.value = isOnSnapTarget()
        _zoomEvents.tryEmit(zoomScale)
    }

    /** Opens a specific page of this notebook (FA-20 page index). */
    fun goToPage(targetPageId: String) {
        if (targetPageId != pageId) viewModelScope.launch { _pageTurnEvents.emit(targetPageId) }
    }

    /** Adds a blank page to the notebook and opens it (FA-20 page-index "+"). */
    fun addPageAndOpen() {
        val repo = repository ?: return
        val nb = notebookId ?: return
        viewModelScope.launch { _pageTurnEvents.emit(repo.addPage(nb).id) }
    }

    /** Toggles a page's bookmark (FA-20 page index). */
    fun setPageBookmark(targetPageId: String, bookmarked: Boolean) {
        val repo = repository ?: return
        viewModelScope.launch { repo.setBookmark(targetPageId, bookmarked) }
    }

    /**
     * Deletes a page from the notebook, keeping at least one (FA-20). If the open page is deleted,
     * navigates to a neighbouring page.
     */
    fun deletePageFromNotebook(targetPageId: String) {
        val repo = repository ?: return
        val pages = _notebookPages.value
        if (pages.size <= 1) return // a notebook always keeps at least one page
        val idx = pages.indexOfFirst { it.id == targetPageId }
        viewModelScope.launch {
            repo.deletePage(targetPageId)
            if (targetPageId == pageId && idx >= 0) {
                val neighbour = pages.getOrNull(idx + 1) ?: pages.getOrNull(idx - 1)
                neighbour?.let { _pageTurnEvents.emit(it.id) }
            }
        }
    }

    /**
     * Deletes several pages at once (FA-20 page-index multi-select), always keeping at least one.
     * If the open page is among them, navigates to a surviving page.
     */
    fun deletePagesFromNotebook(targetIds: Set<String>) {
        val repo = repository ?: return
        val pages = _notebookPages.value
        val toDelete = pages.filter { it.id in targetIds }
        val survivors = pages.filterNot { it.id in targetIds }
        if (toDelete.isEmpty() || survivors.isEmpty()) return // never delete the whole notebook
        viewModelScope.launch {
            toDelete.forEach { repo.deletePage(it.id) }
            if (pageId in targetIds) _pageTurnEvents.emit(survivors.first().id)
        }
    }

    /** Commits a full page order (FA-20 page-index drag-reorder); [orderedIds] is the new sequence. */
    fun reorderPages(orderedIds: List<String>) {
        val repo = repository ?: return
        if (orderedIds.isEmpty()) return
        viewModelScope.launch { repo.reorderPages(orderedIds) }
    }

    /** Moves a page one position earlier/later in the notebook (FA-20 page-index reorder). */
    fun movePage(targetPageId: String, forward: Boolean) {
        val repo = repository ?: return
        val pages = _notebookPages.value
        val idx = pages.indexOfFirst { it.id == targetPageId }
        if (idx < 0) return
        val swapIdx = if (forward) idx + 1 else idx - 1
        if (swapIdx < 0 || swapIdx > pages.lastIndex) return
        val newOrder = pages.map { it.id }.toMutableList()
        newOrder[idx] = pages[swapIdx].id
        newOrder[swapIdx] = pages[idx].id
        viewModelScope.launch { repo.reorderPages(newOrder) }
    }

    /**
     * Turns to the previous/next page (FA-20). Forward past the last page creates a new blank page —
     * but only when the current page has content, so repeatedly swiping past the end never spawns
     * blank pages. Emits the target page id for the screen to navigate to.
     */
    fun turnPage(forward: Boolean) {
        viewModelScope.launch { pageTurnTarget(forward)?.let { _pageTurnEvents.emit(it) } }
    }

    /**
     * The page to turn to in [forward] direction, or null if there's none (backward at the first
     * page; forward at the last page with no content). Forward past the last page with content
     * creates a new blank page — so repeatedly swiping past the end never spawns blank pages.
     */
    private suspend fun pageTurnTarget(forward: Boolean): String? {
        val repo = repository ?: return null
        val nb = notebookId ?: return null
        val pages = _notebookPages.value
        val idx = pages.indexOfFirst { it.id == pageId }
        if (idx < 0) return null
        return when {
            forward && idx < pages.lastIndex -> pages[idx + 1].id
            forward -> {
                val hasContent = _finishedStrokes.value.isNotEmpty() ||
                    _aiNotes.value.any { !it.isError }
                if (hasContent) repo.addPage(nb).id else null
            }
            !forward && idx > 0 -> pages[idx - 1].id
            else -> null
        }
    }

    /**
     * Live horizontal page-turn slide: the page follows the finger by [dxScreen] px (FA-20). No-op in
     * VERTICAL-continuous mode, where pages are reached by scrolling, not by a horizontal swipe.
     */
    fun swipeBy(dxScreen: Float) {
        if (_pageNavigationMode.value == PageNavigationMode.VERTICAL) return
        swipeSettleJob?.cancel()
        val limit = if (pageWidthPx > 0f) pageWidthPx else canvasWidthPx
        swipeOffsetX = (swipeOffsetX + dxScreen).coerceIn(-limit, limit)
        refreshPageTransform()
    }

    /**
     * Releases a horizontal swipe (FA-20): past the commit threshold — and only if a page exists that
     * way — it slides the page off and turns; otherwise it springs elastically back to centre. A swipe
     * toward a page that can't be turned to (the first/last edge) also springs back, so the gesture
     * always resolves cleanly.
     */
    fun releaseSwipe() {
        if (_pageNavigationMode.value == PageNavigationMode.VERTICAL) return
        swipeSettleJob?.cancel()
        swipeSettleJob = viewModelScope.launch {
            val forward = swipeOffsetX < 0f
            val target =
                if (abs(swipeOffsetX) > PAGE_SWIPE_COMMIT_PX) pageTurnTarget(forward) else null
            if (target != null) {
                // Slide the current page fully off, then hand over to the target page (it opens
                // centred in a fresh transform — the residual is covered by the slide-off).
                animateSwipeTo(if (forward) -pageWidthPx else pageWidthPx, SWIPE_SLIDE_OFF_MS)
                _pageTurnEvents.emit(target)
            } else {
                animateSwipeTo(0f, SWIPE_SNAP_BACK_MS) // elastic snap-back
            }
        }
    }

    /** Animates [swipeOffsetX] to [target] over [durationMs] with an ease-out (the spring feel). */
    private suspend fun animateSwipeTo(target: Float, durationMs: Long) {
        val start = swipeOffsetX
        val dist = target - start
        if (dist != 0f) {
            val frames = (durationMs / SWIPE_FRAME_MS).toInt().coerceAtLeast(1)
            for (i in 1..frames) {
                val t = i / frames.toFloat()
                val eased = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
                swipeOffsetX = start + dist * eased
                refreshPageTransform()
                delay(SWIPE_FRAME_MS)
            }
        }
        swipeOffsetX = target
        refreshPageTransform()
    }

    private fun defaultNoteWidth(): Float {
        val usable = pageWidthPx - 2 * AI_NOTE_MARGIN_PX
        return if (usable > AiInkNote.MIN_WIDTH_PX) usable else AiInkNote.FALLBACK_WIDTH_PX
    }

    /**
     * Largest width a box whose left edge is at [x] (page coords) can take while still leaving a
     * margin at the right page edge. Returns [Float.MAX_VALUE] when the page width isn't known yet
     * (e.g. unit tests), so geometry is only ever clamped once the real page width has been reported.
     */
    private fun maxWidthAt(x: Float): Float =
        if (pageWidthPx > 0f) {
            (pageWidthPx - x - AI_NOTE_MARGIN_PX).coerceAtLeast(AiInkNote.MIN_WIDTH_PX)
        } else {
            Float.MAX_VALUE
        }

    @OptIn(FlowPreview::class)
    private fun startAutoSave(repository: NoteRepository, pageId: String) {
        viewModelScope.launch {
            _finishedStrokes.debounce(autoSaveDebounceMillis).collect { strokes ->
                if (strokes != lastPersisted) {
                    val saved = runCatching { repository.replaceStrokes(pageId, strokes) }.isSuccess
                    if (saved) {
                        lastPersisted = strokes
                        // The persisted ink changed — refresh the note-card thumbnail (reads the
                        // freshly-saved strokes, so it runs after the write).
                        generateThumbnail(pageId)
                        // Kick off background TODO/calendar extraction only when genuinely new
                        // pen ink was written — never for lasso edits (move/scale/duplicate/
                        // paste/…), so pasted/duplicated ink isn't re-run by the extractor.
                        if (strokes.isNotEmpty() && contentDirtyForExtraction) {
                            contentDirtyForExtraction = false
                            enqueueExtraction?.invoke(pageId)
                        }
                    }
                }
            }
        }
        viewModelScope.launch {
            _aiNotes.debounce(autoSaveDebounceMillis).collect { notes ->
                // Error notes (e.g. "request unclear") are transient affordances — never persist them.
                val persistable = notes.filterNot { it.isError }
                if (persistable != lastPersistedAiNotes) {
                    runCatching { repository.replaceAiNotes(pageId, persistable) }
                        .onSuccess { lastPersistedAiNotes = persistable }
                }
            }
        }
    }

    /** (Re)generates + caches the page thumbnail, clearing [thumbnailDirty]. No-op without a generator. */
    private suspend fun generateThumbnail(pageId: String) {
        val generate = thumbnailGenerator ?: return
        thumbnailDirty = false
        runCatching { generate(pageId) }
    }

    override fun onCleared() {
        // Final flush so a quick back-press inside the debounce window isn't lost.
        val repository = repository
        val pageId = pageId
        val strokes = _finishedStrokes.value
        val aiNotes = _aiNotes.value.filterNot { it.isError }
        val needsThumbnail = thumbnailDirty
        val generateThumbnail = thumbnailGenerator
        if (repository != null && pageId != null) {
            if (strokes != lastPersisted || needsThumbnail) {
                // Flush the strokes, THEN regenerate the thumbnail (it reads the just-saved strokes),
                // so a back-press inside the autosave debounce still leaves a fresh thumbnail.
                flushScope.launch {
                    if (strokes != lastPersisted) runCatching { repository.replaceStrokes(pageId, strokes) }
                    if (needsThumbnail && generateThumbnail != null) {
                        runCatching { generateThumbnail(pageId) }
                    }
                }
            }
            if (aiNotes != lastPersistedAiNotes) {
                flushScope.launch { runCatching { repository.replaceAiNotes(pageId, aiNotes) } }
            }
        }
        // Release the native handwriting recognizer held for this screen.
        runCatching { recognizer?.close() }
        super.onCleared()
    }

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
        // Remember the tool being left so the FA-19 "last tool swap" gesture can restore it.
        if (tool != _tool.value) previousTool = _tool.value
        _tool.value = tool
        // The lasso tool's transient state (selection + clipboard) lives only while it's active.
        if (tool != CanvasTool.LASSO) {
            clearSelection()
            clearClipboard()
        }
    }

    /**
     * True when the double-tap for [fingerCount] (2 or 3) fingers is bound to an action. InkCanvas
     * reads this so it can fire a single tap instantly when its double is unbound, rather than
     * always waiting out the double-tap window just to rule out a double that does nothing (FA-19).
     */
    fun isDoubleTapBound(fingerCount: Int): Boolean = when (fingerCount) {
        2 -> _twoFingerDoubleTapAction.value != FingerGestureAction.NONE
        3 -> _threeFingerDoubleTapAction.value != FingerGestureAction.NONE
        else -> false
    }

    /** Performs the user-bound action for a detected finger gesture (FA-19). */
    fun onFingerGesture(gesture: FingerGesture) {
        val action = when (gesture) {
            FingerGesture.TwoFingerTap -> _twoFingerTapAction.value
            FingerGesture.ThreeFingerTap -> _threeFingerTapAction.value
            FingerGesture.TwoFingerDoubleTap -> _twoFingerDoubleTapAction.value
            FingerGesture.ThreeFingerDoubleTap -> _threeFingerDoubleTapAction.value
        }
        performGestureAction(action)
    }

    /**
     * Runs a bound gesture action. When [toggleTool] is set and the action selects a tool the
     * canvas is *already* on, it cycles back to the previous tool instead — so a repeat of the same
     * gesture leaves the tool it entered (used by the S Pen double-click).
     */
    private fun performGestureAction(action: FingerGestureAction, toggleTool: Boolean = false) {
        val targetTool = when (action) {
            FingerGestureAction.SELECT_PEN -> CanvasTool.PEN
            FingerGestureAction.SELECT_ERASER -> CanvasTool.ERASER
            FingerGestureAction.SELECT_LASSO -> CanvasTool.LASSO
            else -> null
        }
        if (toggleTool && targetTool != null && _tool.value == targetTool) {
            selectTool(previousTool)
            return
        }
        when (action) {
            FingerGestureAction.NONE -> Unit
            FingerGestureAction.UNDO -> undo()
            FingerGestureAction.REDO -> redo()
            FingerGestureAction.LAST_TOOL_SWAP -> selectTool(previousTool)
            FingerGestureAction.SELECT_PEN -> selectTool(CanvasTool.PEN)
            FingerGestureAction.SELECT_ERASER -> selectTool(CanvasTool.ERASER)
            FingerGestureAction.SELECT_LASSO -> selectTool(CanvasTool.LASSO)
            FingerGestureAction.SELECT_HAND -> setStylusOnly(false)
        }
    }

    // ── S Pen button gestures (FA-19) ─────────────────────────────────────────────────────────

    /** True when a double S Pen button click is bound — InkCanvas uses this to skip the wait. */
    fun isStylusDoubleClickBound(): Boolean = _stylusDoubleClickAction.value != FingerGestureAction.NONE

    /**
     * Performs the user-bound action for a single or double S Pen button click. A double click
     * *toggles* a tool binding: clicking again while already on the bound tool cycles back to the
     * previous tool (Lasso → previous → Lasso …), so the same gesture both enters and leaves it.
     */
    fun onStylusClick(doubleClick: Boolean) {
        if (doubleClick) {
            performGestureAction(_stylusDoubleClickAction.value, toggleTool = true)
        } else {
            performGestureAction(_stylusSingleClickAction.value)
        }
    }

    /**
     * Begins a momentary button-hold: springs to the bound tool ([stylusHoldTool]) and remembers the
     * tool to revert to on [onStylusHoldEnd]. No-op when the gesture is disabled (NONE) or a hold is
     * already active.
     */
    fun onStylusHoldStart() {
        if (toolBeforeHold != null) return
        val target = _stylusHoldTool.value.toCanvasTool() ?: return
        toolBeforeHold = _tool.value
        selectTool(target)
    }

    /** Ends a momentary button-hold: reverts to the tool active before the hold began. */
    fun onStylusHoldEnd() {
        val revert = toolBeforeHold ?: return
        toolBeforeHold = null
        selectTool(revert)
    }

    fun setStylusOnly(enabled: Boolean) {
        _stylusOnly.value = enabled
        persistStylusOnly?.let { writer -> viewModelScope.launch { writer(enabled) } }
    }

    /**
     * Called by the canvas when wet strokes complete and become dry strokes. Always edits the OPEN
     * page: in vertical-continuous mode the open page tracks the scroll (route-nav follows-scroll), so
     * the page you're drawing on is the open one — keeping the full single-page pipeline (undo/redo,
     * /Q, lasso, thumbnails) correct on every page (FA-20).
     */
    fun onStrokesFinished(strokes: Collection<Stroke>) {
        if (strokes.isEmpty()) return
        pushUndoSnapshot(_finishedStrokes.value)
        clearSelection() // a fresh pen stroke isn't part of any lasso selection
        contentDirtyForExtraction = true // genuinely new ink — eligible for background extraction
        thumbnailDirty = true // ink changed — the note-card thumbnail is now stale
        val newStrokes = strokes.map { CanvasStroke(newStrokeId(), it) }
        _finishedStrokes.update { current -> current + newStrokes }

        // Prefix `/Q`: while listening, every new stroke is part of the question — track its id and
        // restart the inactivity timer (don't run the trigger detector; the timer drives the send).
        val listening = _prefixTriggerState.value
        if (listening is PrefixTriggerState.Listening) {
            _prefixTriggerState.value =
                listening.copy(promptStrokeIds = listening.promptStrokeIds + newStrokes.map { it.id })
            prefixNoPromptJob?.cancel() // a prompt stroke arrived — the no-prompt window is moot
            resetPrefixInactivityTimer()
            return
        }
        scheduleTriggerDetection()
    }

    /**
     * Called when the pen touches down to begin a stroke. During prefix `/Q` listening this holds
     * off the inactivity send: the timer only restarts once the stroke *finishes*
     * ([onStrokesFinished]), so it can never elapse while the pen is mid-stroke or paused between
     * the strokes of the question — the AI waits until writing has genuinely stopped (and so always
     * sees the whole question plus the page context).
     */
    fun onWritingStarted() {
        if (_prefixTriggerState.value is PrefixTriggerState.Listening) {
            prefixInactivityJob?.cancel()
        }
    }

    /** Marks the start of an eraser gesture so all its removals undo as one step. */
    fun beginEraseGesture() {
        eraseGestureSnapshot = _finishedStrokes.value
    }

    /** Erase any stroke whose geometry intersects the eraser position. */
    fun eraseAt(x: Float, y: Float, radius: Float = ERASER_RADIUS) {
        // The eraser arrives in screen coords; strokes are stored in page coords. Map screen→page
        // through the transform (centring offset + scroll), so the hit-test lands on the ink the user
        // actually sees, in any orientation/scroll position (FA-20 B3).
        val t = _pageTransform.value
        val worldX = t.screenToPageX(x)
        val worldY = t.screenToPageY(y)
        val pageRadius = t.screenToPageLength(radius)
        val before = _finishedStrokes.value
        val eraserBox = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(worldX, worldY),
            pageRadius * 2,
            pageRadius * 2,
        )
        val after = before.filterNot { cs ->
            cs.stroke.shape.computeCoverageIsGreaterThan(eraserBox, 0f)
        }
        if (after.size == before.size) return

        val snapshot = eraseGestureSnapshot ?: before
        eraseGestureSnapshot = null // only one undo step per gesture
        pushUndoSnapshot(snapshot)
        clearSelection() // an erased stroke may have been selected
        _finishedStrokes.value = after
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(currentSnapshot())
        restoreSnapshot(snapshot)
        clearSelection() // selection may reference strokes/notes the undo changed
        updateHistoryFlags()
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(currentSnapshot())
        restoreSnapshot(snapshot)
        clearSelection()
        updateHistoryFlags()
    }

    fun clearPage() {
        triggerDetectionJob?.cancel()
        cancelPrefixListening()
        // Undoable when there's anything to clear — strokes OR AI boxes (FA-21).
        if (_finishedStrokes.value.isNotEmpty() || _aiNotes.value.any { !it.isError }) {
            pushUndoSnapshot()
        }
        _finishedStrokes.value = emptyList()
        _aiNotes.value = emptyList()
        _aiState.value = AiUiState.Idle
        lastHandledPrompt = null
        clearSelection()
        clearClipboard()
    }

    /** Stops any prefix-listening session and its timers (no stroke removal); resets to Idle. */
    private fun cancelPrefixListening() {
        prefixInactivityJob?.cancel()
        prefixNoPromptJob?.cancel()
        consumedPrefixTriggerIds.clear()
        if (_prefixTriggerState.value != PrefixTriggerState.Idle) {
            _prefixTriggerState.value = PrefixTriggerState.Idle
        }
    }

    fun dismissAiResponse() {
        _aiState.value = AiUiState.Idle
    }

    // --- AI note manipulation ---

    /**
     * Reflow resize (FA-21, single-AI-box left/right edge drag): sets the box's left edge to [x] and
     * its width cap to [widthPx] (both page-space), at a constant font size — so the text re-wraps and
     * the height follows (heightPx cleared to wrap-to-content). Width is clamped so the box stays on
     * the page; the selection bounds re-hug it once the view reports its new measured size.
     */
    fun reflowAiNoteWidth(id: String, x: Float, widthPx: Float) {
        _aiNotes.update { notes ->
            notes.map { note ->
                if (note.id == id) {
                    val newX = x.coerceAtLeast(0f)
                    val newWidth = widthPx.coerceIn(AiInkNote.MIN_WIDTH_PX, maxWidthAt(newX))
                    note.copy(x = newX, widthPx = newWidth, heightPx = null)
                } else {
                    note
                }
            }
        }
    }

    fun removeAiNote(id: String) {
        aiNoteMeasured.remove(id)
        _aiNotes.update { notes -> notes.filterNot { it.id == id } }
        // Drop it from any selection so stale chrome doesn't linger.
        _selection.update { sel ->
            if (sel != null && id in sel.aiNoteIds) {
                if (sel.ids.isEmpty() && sel.aiNoteIds.size == 1) null
                else sel.copy(aiNoteIds = sel.aiNoteIds - id)
            } else {
                sel
            }
        }
    }

    /**
     * The view reports an AI note's on-screen size (FA-21), converted to page-space px by the caller.
     * Stored for bounds + hit-testing; while a lone AI box is selected and idle, its selection box is
     * re-hugged so the dashed border tracks a reflow.
     */
    fun reportAiNoteMeasuredSize(id: String, widthPx: Float, heightPx: Float) {
        if (widthPx <= 0f || heightPx <= 0f) return
        // Skip the rehug work (list scan + emit) on a no-op relayout (e.g. a pure recomposition).
        val prev = aiNoteMeasured[id]
        if (prev != null && prev.first == widthPx && prev.second == heightPx) return
        aiNoteMeasured[id] = widthPx to heightPx
        val sel = _selection.value ?: return
        if (sel.isSingleAiNote && id in sel.aiNoteIds && sel.transform.isIdentity) {
            val note = _aiNotes.value.firstOrNull { it.id == id } ?: return
            _selection.value = sel.copy(bounds = aiNoteRect(note))
        }
    }

    /** Selects a single AI box (FA-21) — the 1.5s press-and-hold path from the canvas. */
    fun selectAiNote(id: String) {
        if (_aiNotes.value.none { it.id == id && !it.isError }) return
        setSelection(emptySet(), setOf(id))
    }

    /** The topmost (last-drawn) non-error AI box containing the page-space point, or null. */
    fun aiNoteAt(pageX: Float, pageY: Float): String? =
        _aiNotes.value.lastOrNull { note ->
            !note.isError && aiNoteRect(note).let {
                pageX in it.left..it.right && pageY in it.top..it.bottom
            }
        }?.id

    /** Page-space bounds of an AI note, using its last measured size when known (FA-21). */
    private fun aiNoteRect(note: AiInkNote): SelectionBounds {
        val measured = aiNoteMeasured[note.id]
        val w = measured?.first ?: note.widthPx
        val h = measured?.second ?: note.heightPx ?: AiInkNote.MIN_HEIGHT_PX
        return SelectionBounds(note.x, note.y, note.x + w, note.y + h)
    }

    // --- Lasso selection tool (FA-9) ---

    /**
     * Selects every stroke whose centroid falls inside the drawn lasso [polygon], expanded to whole
     * groups. An empty lasso (encloses nothing) just clears the current selection. The lasso path is
     * a gesture captured in the UI — it is never committed as ink.
     */
    fun selectByLasso(polygon: List<GestureTriggerDetector.Point>) {
        if (polygon.size < 3) {
            clearSelection()
            return
        }
        val strokes = _finishedStrokes.value
        val enclosedStrokes = if (strokes.isEmpty()) {
            emptySet()
        } else {
            val ids = strokes.map { it.id }
            val centroids = strokes.map { centroidOf(it.stroke) }
            StrokeSelection.expandToGroups(StrokeSelection.enclosedIds(polygon, ids, centroids), strokes)
        }
        // AI boxes are selectable too (FA-21): a lasso can grab them alone or alongside strokes, by
        // their centre point.
        val notes = _aiNotes.value.filterNot { it.isError }
        val enclosedNotes = if (notes.isEmpty()) {
            emptySet()
        } else {
            val noteIds = notes.map { it.id }
            val centres = notes.map { aiNoteRect(it).let { r -> GestureTriggerDetector.Point(r.centerX, r.centerY) } }
            StrokeSelection.enclosedIds(polygon, noteIds, centres)
        }
        if (enclosedStrokes.isEmpty() && enclosedNotes.isEmpty()) {
            clearSelection()
            return
        }
        setSelection(enclosedStrokes, enclosedNotes)
    }

    fun clearSelection() {
        if (_selection.value != null) _selection.value = null
    }

    /** Live move/scale preview while a transform gesture is in progress (no bake, no save yet). */
    fun previewTransform(transform: LiveTransform) {
        _selection.update { it?.copy(transform = transform) }
    }

    /** Abandons an in-progress transform preview without baking it. */
    fun cancelTransform() {
        _selection.update { it?.copy(transform = LiveTransform.IDENTITY) }
    }

    /** Bakes the previewed move/scale into the selected strokes + AI boxes once (FA-9 / FA-21). */
    fun commitTransform() {
        val sel = _selection.value ?: return
        val t = sel.transform
        if (t.isIdentity) return
        // Snap-back (FA-10): a small stroke move released near its origin reverts with nothing
        // applied. The move transform is now in PAGE space (the drag was divided by zoom — FA-21), so
        // normalise by the page-space canvas size (screen ÷ scale); a screen-px canvas would
        // mis-judge the travel at zoom != 1. AI boxes never snap — a small deliberate reposition of
        // an answer must stick. Unknown canvas size (unit tests) and scales always commit.
        val zoom = pageTransform.value.scale.takeIf { it > 0f } ?: 1f
        if (lassoSnapBackEnabled && sel.aiNoteIds.isEmpty() &&
            StrokeSelection.shouldSnapBack(t, canvasWidthPx / zoom, canvasHeightPx / zoom, lassoSnapBackThreshold)
        ) {
            _selection.update { it?.copy(transform = LiveTransform.IDENTITY) }
            return
        }
        // One undoable step covering BOTH strokes and AI boxes (captured pre-change).
        pushUndoSnapshot()
        if (sel.ids.isNotEmpty()) {
            _finishedStrokes.update { current ->
                current.map { cs ->
                    if (cs.id in sel.ids) cs.copy(stroke = strokeTransformer(cs.stroke, t)) else cs
                }
            }
        }
        if (sel.aiNoteIds.isNotEmpty()) {
            // Scaling an AI box scales its font with it (FA-21), so a group/corner scale grows or
            // shrinks the text to keep the box's proportions.
            _aiNotes.update { notes ->
                notes.map { if (it.id in sel.aiNoteIds) transformAiNote(it, t) else it }
            }
        }
        _selection.value = sel.copy(
            transform = LiveTransform.IDENTITY,
            bounds = recomputeSelectionBounds(sel.ids, sel.aiNoteIds) ?: sel.displayBounds,
        )
    }

    /** Applies a baked move/scale to an AI box (FA-21): position via [t]; a scale grows width + font. */
    private fun transformAiNote(note: AiInkNote, t: LiveTransform): AiInkNote {
        val newX = t.applyX(note.x).coerceAtLeast(0f)
        val newY = t.applyY(note.y).coerceAtLeast(0f)
        // A pure MOVE must never resize the text. (widthPx is the wrap CAP, usually wider than the
        // hugged box, so a page-edge width clamp here would silently shrink the font on every move.)
        if (t.scaleX == 1f && t.scaleY == 1f) {
            return note.copy(x = newX, y = newY)
        }
        // A scale grows width cap AND font by the same factor so the box stays proportional and the
        // text keeps fitting (no page-edge clamp — that fought the scale; the box can be moved back
        // if it runs off the edge).
        val newWidth = (note.widthPx * t.scaleX).coerceAtLeast(AiInkNote.MIN_WIDTH_PX)
        val newHeight = note.heightPx?.let { (it * t.scaleY).coerceAtLeast(AiInkNote.MIN_HEIGHT_PX) }
        val newFont = (note.fontScale * t.brushScale)
            .coerceIn(AiInkNote.MIN_FONT_SCALE, AiInkNote.MAX_FONT_SCALE)
        return note.copy(x = newX, y = newY, widthPx = newWidth, heightPx = newHeight, fontScale = newFont)
    }

    /** Duplicate: clones the selection in place (offset), preserving grouping; selects the copy. */
    fun duplicateSelection() {
        val sel = _selection.value ?: return
        val strokeCopies = cloneStrokes(
            _finishedStrokes.value.filter { it.id in sel.ids },
            DUPLICATE_OFFSET_PX,
            DUPLICATE_OFFSET_PX,
        )
        val noteCopies = cloneAiNotes(
            _aiNotes.value.filter { it.id in sel.aiNoteIds },
            DUPLICATE_OFFSET_PX,
            DUPLICATE_OFFSET_PX,
        )
        if (strokeCopies.isEmpty() && noteCopies.isEmpty()) return
        pushUndoSnapshot()
        if (strokeCopies.isNotEmpty()) _finishedStrokes.update { it + strokeCopies }
        if (noteCopies.isNotEmpty()) _aiNotes.update { it + noteCopies }
        setSelection(strokeCopies.map { it.id }.toSet(), noteCopies.map { it.id }.toSet())
    }

    /** Bin: removes the selected strokes and AI boxes. */
    fun deleteSelection() {
        val sel = _selection.value ?: return
        removeSelected(sel)
        clearSelection()
    }

    /** Removes the selection's strokes and AI boxes from the page in one undoable step (FA-21). */
    private fun removeSelected(sel: SelectionState) {
        pushUndoSnapshot() // covers strokes AND AI boxes, so a mixed delete fully undoes
        if (sel.ids.isNotEmpty()) {
            _finishedStrokes.update { current -> current.filterNot { it.id in sel.ids } }
        }
        if (sel.aiNoteIds.isNotEmpty()) {
            sel.aiNoteIds.forEach { aiNoteMeasured.remove(it) }
            _aiNotes.update { current -> current.filterNot { it.id in sel.aiNoteIds } }
        }
    }

    /** Pushes one undo step before a live edge-reflow drag begins, so the reflow is undoable (FA-21). */
    fun beginAiNoteReflow() {
        pushUndoSnapshot()
    }

    /** Copy: holds deep copies of the selection on the clipboard; the selection stays put. */
    fun copySelection() {
        val sel = _selection.value ?: return
        captureClipboard(sel.ids, sel.aiNoteIds)
    }

    /** Cut: copies the selection to the clipboard, then removes it from the page. */
    fun cutSelection() {
        val sel = _selection.value ?: return
        captureClipboard(sel.ids, sel.aiNoteIds)
        removeSelected(sel)
        clearSelection()
    }

    /**
     * Paste: stamps the clipboard at ([x], [y]) (its bounds' top-left lands there), preserving group
     * structure, and selects the pasted copy so it can be dragged immediately. The clipboard is kept
     * (repeatable) until [clearClipboard]. Pasted ink reuses existing content, so — like all lasso
     * edits — it never triggers a fresh background extraction.
     */
    fun pasteAt(x: Float, y: Float) {
        val bounds = clipboardBounds ?: return
        if (clipboardStrokes.isEmpty() && clipboardAiNotes.isEmpty()) return
        val dx = x - bounds.left
        val dy = y - bounds.top
        val pastedStrokes = cloneStrokes(clipboardStrokes, dx, dy)
        val pastedNotes = cloneAiNotes(clipboardAiNotes, dx, dy)
        if (pastedStrokes.isEmpty() && pastedNotes.isEmpty()) return
        pushUndoSnapshot()
        if (pastedStrokes.isNotEmpty()) _finishedStrokes.update { it + pastedStrokes }
        if (pastedNotes.isNotEmpty()) _aiNotes.update { it + pastedNotes }
        setSelection(pastedStrokes.map { it.id }.toSet(), pastedNotes.map { it.id }.toSet())
    }

    /** Clears the clipboard, deselects, and resets the lasso tool to its idle select state. */
    fun clearClipboard() {
        clipboardStrokes = emptyList()
        clipboardAiNotes = emptyList()
        clipboardBounds = null
        if (_clipboard.value.active) _clipboard.value = ClipboardState.EMPTY
        clearSelection()
    }

    /** Group: gives every selected stroke one shared (new) group id, so they select together. */
    fun groupSelection() {
        val sel = _selection.value ?: return
        if (sel.ids.size < 2) return
        val groupId = newGroupId()
        _finishedStrokes.update { current ->
            current.map { if (it.id in sel.ids) it.copy(groupId = groupId) else it }
        }
        _selection.update { it?.copy(grouped = true) }
    }

    /** Ungroup: clears the group id on every selected stroke. */
    fun ungroupSelection() {
        val sel = _selection.value ?: return
        _finishedStrokes.update { current ->
            current.map { if (it.id in sel.ids) it.copy(groupId = null) else it }
        }
        _selection.update { it?.copy(grouped = false) }
    }

    /** Toggles aspect-ratio lock for corner-handle scaling. */
    fun setLockRatio(locked: Boolean) {
        _selection.update { it?.copy(lockRatio = locked) }
    }

    /** AI prompt: recognizes the selected handwriting and sends it to the AI (a deliberate query). */
    fun aiPromptSelection() {
        val recognizer = recognizer ?: return
        val sel = _selection.value ?: return
        val selected = _finishedStrokes.value.filter { it.id in sel.ids }.map { it.stroke }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val question = lineSplitter(selected)
                .mapNotNull { recognizer.recognize(it).getOrNull()?.trim()?.ifEmpty { null } }
                .joinToString(" ")
                .ifBlank { null } ?: return@launch
            submitQuery(question, question, notePlacer(selected), bypassDedup = true)
        }
    }

    // --- Selection internals ---

    /** Builds [SelectionState] for [ids] + [aiNoteIds]: union bounds + whether it's one stroke group. */
    private fun setSelection(ids: Set<String>, aiNoteIds: Set<String> = emptySet()) {
        val strokes = _finishedStrokes.value.filter { it.id in ids }
        val notes = _aiNotes.value.filter { it.id in aiNoteIds && !it.isError }
        val bounds = recomputeSelectionBounds(ids, notes.map { it.id }.toSet())
        if (bounds == null) {
            clearSelection()
            return
        }
        val groupIds = strokes.map { it.groupId }.toSet()
        // Grouping is a stroke-only concept; a selection containing an AI box is never "grouped".
        val grouped = notes.isEmpty() && groupIds.size == 1 && groupIds.single() != null
        _selection.value = SelectionState(
            ids = strokes.map { it.id }.toSet(),
            aiNoteIds = notes.map { it.id }.toSet(),
            bounds = bounds,
            lockRatio = _selection.value?.lockRatio ?: true, // keep the user's lock preference (FA-21: default on)
            grouped = grouped,
        )
    }

    private fun captureClipboard(ids: Set<String>, aiNoteIds: Set<String>) {
        val strokes = _finishedStrokes.value.filter { it.id in ids }
        val notes = _aiNotes.value.filter { it.id in aiNoteIds && !it.isError }
        if (strokes.isEmpty() && notes.isEmpty()) return
        clipboardStrokes = strokes
        clipboardAiNotes = notes
        clipboardBounds = recomputeSelectionBounds(ids, notes.map { it.id }.toSet())
        _clipboard.value = ClipboardState(count = strokes.size + notes.size)
    }

    /** Union of the selected strokes' + AI notes' current page-space bounds; null when both empty. */
    private fun recomputeSelectionBounds(ids: Set<String>, aiNoteIds: Set<String>): SelectionBounds? {
        val strokeBoxes = if (ids.isEmpty()) emptyList() else {
            _finishedStrokes.value.filter { it.id in ids }.map { strokeBoundsOf(it.stroke) }
        }
        val noteBoxes = if (aiNoteIds.isEmpty()) emptyList() else {
            _aiNotes.value.filter { it.id in aiNoteIds }.map { aiNoteRect(it) }
        }
        return StrokeSelection.union(strokeBoxes + noteBoxes)
    }

    /** Deep-copies [source] AI notes shifted by ([dx], [dy]), each with a fresh id (FA-21). */
    private fun cloneAiNotes(source: List<AiInkNote>, dx: Float, dy: Float): List<AiInkNote> =
        source.map { it.copy(id = newStrokeId(), x = it.x + dx, y = it.y + dy) }

    /**
     * Deep-copies [source] shifted by ([dx], [dy]), giving each copy a fresh id and remapping group
     * ids so the copy keeps its internal grouping without merging into the originals' group.
     */
    private fun cloneStrokes(source: List<CanvasStroke>, dx: Float, dy: Float): List<CanvasStroke> {
        if (source.isEmpty()) return emptyList()
        val remap = HashMap<String, String>()
        val t = LiveTransform(dx = dx, dy = dy)
        return source.map { cs ->
            CanvasStroke(
                id = newStrokeId(),
                stroke = strokeTransformer(cs.stroke, t),
                groupId = cs.groupId?.let { remap.getOrPut(it) { newGroupId() } },
            )
        }
    }

    private fun newStrokeId(): String = UUID.randomUUID().toString()
    private fun newGroupId(): String = UUID.randomUUID().toString()

    // --- History internals ---

    /** One undo step: the strokes and (persistable) AI boxes at the time of the snapshot (FA-21). */
    private data class HistorySnapshot(
        val strokes: List<CanvasStroke>,
        val aiNotes: List<AiInkNote>,
    )

    private fun currentSnapshot(): HistorySnapshot =
        HistorySnapshot(_finishedStrokes.value, _aiNotes.value.filterNot { it.isError })

    /** Restores a snapshot, keeping any live (transient) error note that isn't part of history. */
    private fun restoreSnapshot(snapshot: HistorySnapshot) {
        _finishedStrokes.value = snapshot.strokes
        _aiNotes.update { current -> snapshot.aiNotes + current.filter { it.isError } }
    }

    /**
     * Pushes one undo step capturing BOTH strokes and AI boxes (FA-21), so a lasso edit that moves /
     * scales / deletes / pastes AI boxes — alone or mixed with strokes — is fully undoable. [strokes]
     * defaults to the current strokes; pass an explicit value when the caller holds a pre-gesture
     * snapshot (e.g. the per-eraser-gesture step).
     */
    private fun pushUndoSnapshot(strokes: List<CanvasStroke> = _finishedStrokes.value) {
        undoStack.addLast(HistorySnapshot(strokes, _aiNotes.value.filterNot { it.isError }))
        if (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    // --- AI trigger pipeline ---

    private fun scheduleTriggerDetection() {
        val recognizer = recognizer ?: return
        triggerDetectionJob?.cancel()
        triggerDetectionJob = viewModelScope.launch {
            delay(triggerDebounceMillis)
            detectAndHandleTrigger(recognizer)
        }
    }

    private suspend fun detectAndHandleTrigger(recognizer: HandwritingRecognizer) {
        if (_finishedStrokes.value.isEmpty()) return
        val strokes = _finishedStrokes.value.map { it.stroke }
        val lines = lineSplitter(strokes)
        if (lines.isEmpty()) return

        when (triggerMode) {
            TriggerMode.COMMAND -> handleCommandTrigger(recognizer, strokes, lines)
            TriggerMode.PREFIX_COMMAND -> handlePrefixTrigger(recognizer, strokes, lines)
            TriggerMode.GESTURE -> handleGestureTrigger(recognizer, strokes)
        }
    }

    /**
     * Written-command activation (`/Q`). Evaluates the top recognition candidates of the
     * last-drawn line so a `/Q` the best guess garbled still fires, then assembles the
     * question (inline, or the multi-line block above a bare trigger) and page context.
     */
    private suspend fun handleCommandTrigger(
        recognizer: HandwritingRecognizer,
        strokes: List<Stroke>,
        lines: List<List<Stroke>>,
    ) {
        // Cheap per-debounce check: only the line containing the last-drawn stroke.
        val lastStroke = strokes.last()
        val triggerIndex = lines.indexOfFirst { line -> line.any { it === lastStroke } }
            .takeIf { it >= 0 } ?: lines.lastIndex
        val triggerLine = lines[triggerIndex]
        val trigger = triggerCommand

        // Top-N candidates: a low-confidence candidate (beyond the rank cap) can't trigger.
        val candidates = recognizer.recognizeCandidates(triggerLine).getOrNull().orEmpty().map { it.text }
        val triggerText = QueryTriggerDetector.firstTriggerCandidate(candidates, trigger) ?: return

        // Question: text before an inline trigger, else the contiguous block of lines above it.
        val inlinePrompt = QueryTriggerDetector.extractPrompt(triggerText, trigger)
        val questionIndices = if (inlinePrompt == null) questionLineSelector(lines, triggerIndex) else emptyList()
        val question = inlinePrompt
            ?: questionIndices
                .mapNotNull { recognizer.recognize(lines[it]).getOrNull()?.trim()?.ifEmpty { null } }
                .joinToString(" ")
                .ifBlank { null }

        // Everything else on the page goes along as context.
        val excluded = questionIndices.toMutableSet().apply { add(triggerIndex) }
        val context = lines.indices
            .filter { it !in excluded }
            .mapNotNull { recognizer.recognize(lines[it]).getOrNull()?.trim()?.ifEmpty { null } }
            .joinToString("\n")

        val effectiveQuestion = question?.ifBlank { null } ?: context.ifBlank { null } ?: return
        val userPrompt = if (question == null || context.isBlank()) {
            effectiveQuestion
        } else {
            "Handwritten question: $effectiveQuestion\n\n" +
                "Other notes on the page (context, may be unrelated):\n$context"
        }
        submitQuery(effectiveQuestion, userPrompt, notePlacer(triggerLine))
    }

    /**
     * Lasso activation: when the last stroke is a closed loop, the strokes it encloses are
     * recognized as the question. The lasso itself is a gesture, not ink, so it's removed
     * from the canvas before the answer is placed.
     */
    private suspend fun handleGestureTrigger(recognizer: HandwritingRecognizer, strokes: List<Stroke>) {
        if (strokes.size < 2) return // need the lasso plus at least one enclosed stroke
        val lassoStroke = strokes.last()
        val polygon = lassoOf(lassoStroke) ?: return // last stroke isn't a deliberate loop
        val content = strokes.dropLast(1)
        val enclosed = GestureTriggerDetector.enclosedIndices(polygon, content.map(centroidOf))
            .map(content::get)
        if (enclosed.isEmpty()) return

        val question = lineSplitter(enclosed)
            .mapNotNull { recognizer.recognize(it).getOrNull()?.trim()?.ifEmpty { null } }
            .joinToString(" ")
            .ifBlank { null } ?: return

        // The lasso is a gesture, not content — take it back off the canvas.
        _finishedStrokes.update { current -> current.filterNot { it.stroke === lassoStroke } }

        // A lasso is a deliberate, explicit request (like pressing a button): re-circling the
        // same selection must always re-run, so it bypasses the unchanged-content de-dupe guard.
        submitQuery(question, question, notePlacer(enclosed), bypassDedup = true)
    }

    /**
     * Prefix activation (`/Q` first, then the question). While [PrefixTriggerState.Idle], scans the
     * recognized lines for a line that is the trigger and nothing else, ignoring lines already
     * consumed by an earlier session. When found, it enters [PrefixTriggerState.Listening]: the
     * trigger line's strokes are recorded for cancel, any strokes already written below it pre-seed
     * the prompt, and the inactivity / no-prompt timer begins. No query is sent yet — the inactivity
     * timer ([firePrefixQuery]) does that once the user pauses.
     *
     * (Scans all lines, not just the last-drawn one like [handleCommandTrigger], so the listening
     * indicator appears even when the user flows straight from the command into the question.)
     */
    private suspend fun handlePrefixTrigger(
        recognizer: HandwritingRecognizer,
        strokes: List<Stroke>,
        lines: List<List<Stroke>>,
    ) {
        if (_prefixTriggerState.value !is PrefixTriggerState.Idle) return

        // Map each Stroke back to its CanvasStroke id (identity — strokes have no value equality).
        val idByStroke = java.util.IdentityHashMap<Stroke, String>()
        _finishedStrokes.value.forEach { idByStroke[it.stroke] = it.id }
        fun lineIds(line: List<Stroke>): List<String> = line.mapNotNull { idByStroke[it] }

        val trigger = triggerCommand
        var triggerIndex = -1
        for (i in lines.indices) {
            // A standalone trigger is tiny; skip long content lines so the scan stays cheap.
            if (lines[i].size > MAX_PREFIX_TRIGGER_STROKES) continue
            val ids = lineIds(lines[i])
            if (ids.isEmpty() || ids.all { it in consumedPrefixTriggerIds }) continue
            val candidates = recognizer.recognizeCandidates(lines[i]).getOrNull().orEmpty().map { it.text }
            if (QueryTriggerDetector.firstStandaloneTriggerCandidate(candidates, trigger) != null) {
                triggerIndex = i
                break
            }
        }
        if (triggerIndex < 0) return

        val triggerIds = lineIds(lines[triggerIndex])
        // Anything already written below the command is the start of the question.
        val promptIds = lines.drop(triggerIndex + 1).flatMap { lineIds(it) }
        consumedPrefixTriggerIds.addAll(triggerIds)
        _prefixTriggerState.value = PrefixTriggerState.Listening(triggerIds, promptIds)
        if (promptIds.isEmpty()) startPrefixNoPromptTimer() else resetPrefixInactivityTimer()
    }

    /** (Re)starts the inactivity countdown; firing sends the prompt written so far. */
    private fun resetPrefixInactivityTimer() {
        prefixInactivityJob?.cancel()
        if (_prefixTriggerState.value !is PrefixTriggerState.Listening) return
        prefixInactivityJob = viewModelScope.launch {
            delay(prefixTriggerDelayMs)
            firePrefixQuery()
        }
    }

    /** Starts the no-prompt timeout: if nothing is written in time, abandon (leave the ink). */
    private fun startPrefixNoPromptTimer() {
        prefixNoPromptJob?.cancel()
        prefixNoPromptJob = viewModelScope.launch {
            delay(prefixNoPromptTimeoutMs)
            val state = _prefixTriggerState.value
            // No question was written — quietly cancel and leave the `/Q` on the canvas as ink.
            if (state is PrefixTriggerState.Listening && state.promptStrokeIds.isEmpty()) {
                _prefixTriggerState.value = PrefixTriggerState.Idle
            }
        }
    }

    /** Inactivity fired: move to Processing and send the recognized question (+ page context). */
    private fun firePrefixQuery() {
        val state = _prefixTriggerState.value as? PrefixTriggerState.Listening ?: return
        if (state.promptStrokeIds.isEmpty()) return
        prefixInactivityJob?.cancel()
        prefixNoPromptJob?.cancel()
        _prefixTriggerState.value = PrefixTriggerState.Processing
        viewModelScope.launch {
            runCatching { runPrefixQuery(state) }
            _prefixTriggerState.value = PrefixTriggerState.Idle
        }
    }

    /** Recognizes the prompt strokes as the question + the rest of the page as context, then asks. */
    private suspend fun runPrefixQuery(state: PrefixTriggerState.Listening) {
        val recognizer = recognizer ?: return
        val byId = _finishedStrokes.value.associateBy { it.id }
        val promptStrokes = state.promptStrokeIds.mapNotNull { byId[it]?.stroke }
        if (promptStrokes.isEmpty()) return

        val question = lineSplitter(promptStrokes)
            .mapNotNull { recognizer.recognize(it).getOrNull()?.trim()?.ifEmpty { null } }
            .joinToString(" ")
            .ifBlank { null } ?: return

        // The command line and the question itself aside, the rest of the page goes along as context.
        val excludedIds = (state.triggerStrokeIds + state.promptStrokeIds).toSet()
        val contextStrokes = _finishedStrokes.value.filterNot { it.id in excludedIds }.map { it.stroke }
        val context = lineSplitter(contextStrokes)
            .mapNotNull { recognizer.recognize(it).getOrNull()?.trim()?.ifEmpty { null } }
            .joinToString("\n")

        val userPrompt = if (context.isBlank()) {
            question
        } else {
            "Handwritten question: $question\n\n" +
                "Other notes on the page (context, may be unrelated):\n$context"
        }
        // The answer lands below the question; a deliberate trigger bypasses the de-dupe guard.
        submitQuery(question, userPrompt, notePlacer(promptStrokes), bypassDedup = true)
    }

    /**
     * The listening indicator's ✕: cancels the session and removes the `/Q` strokes AND everything
     * written after it (the in-progress question). Strokes before `/Q`, and any drawn after the
     * cancelled block, are untouched. Only callable while [PrefixTriggerState.Listening].
     */
    fun cancelPrefixTrigger() {
        val state = _prefixTriggerState.value as? PrefixTriggerState.Listening ?: return
        prefixInactivityJob?.cancel()
        prefixNoPromptJob?.cancel()
        val idsToRemove = (state.triggerStrokeIds + state.promptStrokeIds).toSet()
        if (idsToRemove.isNotEmpty()) {
            pushUndoSnapshot(_finishedStrokes.value) // make the removal undoable
            _finishedStrokes.update { current -> current.filterNot { it.id in idsToRemove } }
        }
        _prefixTriggerState.value = PrefixTriggerState.Idle
    }

    /**
     * Shared tail of both activation paths: de-dupes against the last prompt, guards on the
     * provider, extracts tasks first (suppressing a `/Q` answer when the page holds new
     * action items), then renders the answer or a connection error onto the canvas.
     */
    private suspend fun submitQuery(
        effectiveQuestion: String,
        userPrompt: String,
        position: NotePosition,
        bypassDedup: Boolean = false,
        suppressGuess: Boolean = false,
    ) {
        // The de-dupe guard stops the debounced detector re-firing on unchanged content. A
        // deliberate, explicit trigger (a lasso, or a re-send) opts out so it always runs.
        if (!bypassDedup && effectiveQuestion == lastHandledPrompt) return
        lastHandledPrompt = effectiveQuestion

        val provider = aiProvider
        if (provider == null) {
            _aiState.value = AiUiState.Error(
                "Anthropic API key not configured. Add anthropic.apiKey=sk-ant-... " +
                    "to local.properties and rebuild.",
            )
            return
        }

        _aiState.value = AiUiState.Thinking(effectiveQuestion, position.x, position.y)

        // Extract-first: if the page holds NEW action items, capture them as a to-do
        // (confirmation sheet) and DO NOT write a /Q answer. If it holds ONLY items that
        // already exist, tell the user with a self-clearing toast (also no answer). Only a
        // genuine question (no actionable items) falls through to a rendered answer.
        when (offerExtraction(pageText = userPrompt)) {
            ExtractionOffer.NEW_ITEMS -> {
                _aiState.value = AiUiState.Idle
                return
            }
            ExtractionOffer.ALL_EXISTING -> {
                showTransientMessage(ALREADY_EXISTS_MESSAGE)
                _aiState.value = AiUiState.Idle
                return
            }
            ExtractionOffer.NONE -> Unit // fall through to a normal answer
        }

        // 15s timeout: null result is treated like a connection failure.
        val result = withTimeoutOrNull(requestTimeoutMillis) {
            provider.generate(AIRequest(input = AIInput.Text(userPrompt), systemPrompt = systemPrompt(unitSystem)))
        }
        when {
            result == null -> _aiState.value = AiUiState.Error(CONNECTION_ERROR, position.x, position.y)
            result.isSuccess -> {
                val answer = result.getOrThrow().text.stripMarkdown()
                val noteId = UUID.randomUUID().toString()
                // Constrain to the page width so a long (e.g. two-part) answer never runs off-screen.
                val width = defaultNoteWidth().coerceAtMost(maxWidthAt(position.x))
                if (isUnclearResponse(answer)) {
                    // Unclear-error response: rendered with a "Did you mean …?" Yes/No clarifier
                    // (when the model offered a guess) falling back to Edit-prompt / Okay. The
                    // message line alone goes in the note body; the guess drives the Yes action.
                    // Transient (not persisted) and never auto-selected — it's an error affordance.
                    _aiNotes.update {
                        it + AiInkNote(
                            id = noteId,
                            text = unclearMessage(answer),
                            x = position.x,
                            y = position.y,
                            widthPx = width,
                            isError = true,
                            sourceQuestion = effectiveQuestion,
                            // Don't offer another guess on a re-sent guess — that's the loop where
                            // the model keeps proposing prompts it then re-flags as unclear. After a
                            // re-send the user only gets Edit prompt / Okay.
                            suggestedQuestion = if (suppressGuess) null else suggestedQuestion(answer),
                        )
                    }
                } else {
                    _aiNotes.update {
                        it + AiInkNote(
                            id = noteId,
                            text = answer,
                            x = position.x,
                            y = position.y,
                            widthPx = width,
                        )
                    }
                    _createdNoteEvents.tryEmit(noteId)
                }
                _aiState.value = AiUiState.Idle
            }
            else -> _aiState.value = AiUiState.Error(CONNECTION_ERROR, position.x, position.y)
        }
    }

    /** Shows a short notification over the canvas, auto-clearing after [TRANSIENT_MESSAGE_MILLIS]. */
    private fun showTransientMessage(message: String) {
        transientMessageJob?.cancel()
        _transientMessage.value = message
        transientMessageJob = viewModelScope.launch {
            delay(TRANSIENT_MESSAGE_MILLIS)
            _transientMessage.value = null
        }
    }

    /**
     * Re-submits an edited prompt from an error note's "Edit prompt" box. Drops the error note,
     * resets the de-dupe guard so the same text can be re-sent, and runs the query again at the
     * error note's position.
     */
    fun resendQuery(noteId: String, editedQuestion: String) {
        val errorNote = _aiNotes.value.firstOrNull { it.id == noteId }
        val position = errorNote?.let { NotePosition(it.x, it.y) }
            ?: NotePosition(AI_NOTE_MARGIN_PX, RESEND_FALLBACK_Y)
        removeAiNote(noteId)
        val text = editedQuestion.trim()
        if (text.isBlank()) return
        lastHandledPrompt = null // an explicit re-send may repeat the previous text
        // suppressGuess: if this re-sent prompt is still unclear, fall back to Edit/Okay rather
        // than offering yet another "Did you mean …?" — that's the circular-clarify loop.
        viewModelScope.launch { submitQuery(text, text, position, suppressGuess = true) }
    }

    /** True for the one-shot "I need more information, request unclear" error response. */
    private fun isUnclearResponse(text: String): Boolean =
        text.contains("request unclear", ignoreCase = true) ||
            text.trim().startsWith("I need more information", ignoreCase = true)

    /** The user-facing message of an unclear response, with any "Did you mean …" line stripped out. */
    private fun unclearMessage(text: String): String =
        text.lineSequence()
            .filterNot { DID_YOU_MEAN_REGEX.containsMatchIn(it) }
            .joinToString("\n")
            .trim()
            .ifBlank { DEFAULT_UNCLEAR_MESSAGE }

    /** The AI's single best guess at the intended question ("Did you mean: …?"), or null. */
    private fun suggestedQuestion(text: String): String? =
        DID_YOU_MEAN_REGEX.find(text)
            ?.groupValues?.get(1)
            ?.trim()?.trimEnd('?')?.trim()
            ?.ifBlank { null }

    /** Outcome of [offerExtraction], so [submitQuery] can suppress the answer and/or notify. */
    private enum class ExtractionOffer {
        /** New action items found — confirmation sheet raised; suppress the answer. */
        NEW_ITEMS,

        /** Items found, but all already exist — notify the user; suppress the answer. */
        ALL_EXISTING,

        /** Nothing actionable — fall through to a normal answer. */
        NONE,
    }

    /**
     * Detects action items on the page and routes them:
     *  - any NEW item → raises the confirmation sheet ([ExtractionOffer.NEW_ITEMS]);
     *  - items found but all already captured → [ExtractionOffer.ALL_EXISTING] (the caller shows
     *    a self-clearing "already exists" notification);
     *  - nothing actionable / no extractor → [ExtractionOffer.NONE].
     *
     * De-dupes against both the to-do list AND items already suggested for this page (so the
     * manual `/Q` path and the background auto-extraction never propose the same item twice —
     * in either order). New items are also recorded as handled suggestions for this page, so a
     * later background run de-dupes them. Independent of `/Q`, so a save-job can reuse it.
     */
    private suspend fun offerExtraction(pageText: String): ExtractionOffer {
        val extractor = taskExtractor ?: return ExtractionOffer.NONE
        val todoRepository = todoRepository ?: return ExtractionOffer.NONE
        val pageId = pageId ?: return ExtractionOffer.NONE
        if (pageText.isBlank()) return ExtractionOffer.NONE

        // Anchor relative dates ("this Monday", "tomorrow") to the device's current date and
        // timezone so the model resolves them correctly instead of guessing.
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        val referenceDate =
            "${today.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)} $today"
        val extracted = extractor.extract(pageText, referenceDate).getOrNull().orEmpty()
        if (extracted.isEmpty()) return ExtractionOffer.NONE

        // Already on the to-do list, or already suggested (incl. background) for this page.
        val existing = buildSet {
            addAll(runCatching { todoRepository.existingContents() }.getOrDefault(emptySet()))
            suggestionRepository?.let {
                addAll(runCatching { it.existingContents(pageId) }.getOrDefault(emptySet()))
            }
        }
        val newTasks = extracted.filter { it.content.trim().lowercase() !in existing }
        if (newTasks.isEmpty()) return ExtractionOffer.ALL_EXISTING // found, but all already captured

        val repoTasks = newTasks.map { task ->
            TodoRepository.ExtractedTask(
                content = task.content,
                priority = TodoPriority.entries.getOrElse(task.priority) { TodoPriority.NONE },
                dueAt = task.dueDateIso.toEpochMillisOrNull(today),
            )
        }
        val title = repository?.getPage(pageId)?.displayTitle() ?: "Note"
        pendingRepoTasks = repoTasks
        pendingSourceTitle = title
        _pendingExtraction.value = PendingTaskExtraction(
            tasks = repoTasks.map { it.content },
            sourcePageTitle = title,
        )
        // Record the proposed items as handled suggestions so the background auto-extraction
        // (which de-dupes against this page's suggestions) can't re-propose the same ones.
        suggestionRepository?.recordHandled(
            newTasks.map { task ->
                PendingSuggestion(
                    pageId = pageId,
                    type = SuggestionType.TODO,
                    content = task.content,
                    x = 0f,
                    y = 0f,
                )
            },
        )
        return ExtractionOffer.NEW_ITEMS
    }

    private var pendingRepoTasks: List<TodoRepository.ExtractedTask> = emptyList()
    private var pendingSourceTitle: String = "Note"

    /**
     * Persists the AI-extracted tasks the user kept selected.
     *
     * @param selectedIndices indices into the pending task list that are toggled on;
     *        null means "all".
     */
    fun confirmExtraction(selectedIndices: Set<Int>? = null) {
        val todoRepository = todoRepository ?: return
        val pageId = pageId ?: return
        val title = pendingSourceTitle
        val chosen = pendingRepoTasks.filterIndexed { index, _ ->
            selectedIndices == null || index in selectedIndices
        }
        clearPendingExtraction()
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                todoRepository.addExtracted(chosen, sourcePageId = pageId, sourcePageTitle = title)
            }
        }
    }

    fun dismissExtraction() = clearPendingExtraction()

    private fun clearPendingExtraction() {
        _pendingExtraction.value = null
        pendingRepoTasks = emptyList()
    }

    // --- Background auto-extraction suggestions (FA-2) ---

    /** "Yes" on a single background suggestion. */
    fun acceptSuggestion(id: String) = acceptSuggestions(listOf(id))

    /** "No" on a single background suggestion. */
    fun rejectSuggestion(id: String) = dismissSuggestions(listOf(id))

    /**
     * The collated confirmation sheet's "Add selected" action: commit the chosen suggestions
     * and dismiss the rest in one go. Both outcomes mark each row *handled* (kept), so the same
     * item is never re-suggested for this page on a later save.
     */
    fun resolveSuggestions(acceptIds: List<String>, dismissIds: List<String>) {
        acceptSuggestions(acceptIds)
        dismissSuggestions(dismissIds)
    }

    /** Commit each suggestion (TODO item / calendar suggestion) and mark its row handled. */
    fun acceptSuggestions(ids: List<String>) {
        if (ids.isEmpty()) return
        val suggestionRepo = suggestionRepository ?: return
        val pageId = pageId ?: return
        viewModelScope.launch {
            val title = repository?.getPage(pageId)?.displayTitle() ?: "Note"
            ids.forEach { id ->
                val suggestion = suggestionRepo.get(id) ?: return@forEach
                commitSuggestion(suggestion, pageId, title)
                suggestionRepo.markHandled(id)
            }
        }
    }

    /** Dismiss each suggestion: keep the row so the same item isn't re-suggested on the next save. */
    fun dismissSuggestions(ids: List<String>) {
        if (ids.isEmpty()) return
        val suggestionRepo = suggestionRepository ?: return
        viewModelScope.launch { ids.forEach { suggestionRepo.dismiss(it) } }
    }

    private suspend fun commitSuggestion(suggestion: PendingSuggestion, pageId: String, title: String) {
        when (suggestion.type) {
            SuggestionType.TODO -> todoRepository?.addExtracted(
                listOf(
                    TodoRepository.ExtractedTask(
                        content = suggestion.content,
                        priority = TodoPriority.entries.getOrElse(suggestion.priority) { TodoPriority.NONE },
                        dueAt = suggestion.dueAtMillis,
                    ),
                ),
                sourcePageId = pageId,
                sourcePageTitle = title,
            )
            SuggestionType.EVENT -> calendarRepository?.addSuggestion(
                CalendarEvent(
                    title = suggestion.content,
                    startTime = suggestion.startMillis ?: 0L,
                    endTime = suggestion.endMillis ?: ((suggestion.startMillis ?: 0L) + 3_600_000L),
                    location = suggestion.location,
                    sourceNoteId = pageId,
                    isAiSuggested = true,
                ),
                sourcePageId = pageId,
            )
        }
    }

    companion object {
        /** Date label shown in the editor header (FA-14), e.g. "8 Feb 2026". */
        private val DATE_LABEL_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

        /** Dark ink for user strokes; AI note ink uses a distinct colour (see ui). */
        const val USER_INK_COLOR: Int = 0xFF1A237E.toInt()
        const val DEFAULT_BRUSH_SIZE: Float = 4f
        const val BRUSH_EPSILON: Float = 0.1f
        const val ERASER_RADIUS: Float = 16f
        const val TRIGGER_DEBOUNCE_MILLIS: Long = 900L

        /** Max strokes a line may have and still be scanned as a possible standalone prefix `/Q`. */
        const val MAX_PREFIX_TRIGGER_STROKES: Int = 6
        const val AUTOSAVE_DEBOUNCE_MILLIS: Long = 800L
        const val REQUEST_TIMEOUT_MILLIS: Long = 15_000L
        const val MAX_HISTORY: Int = 50

        /** How long a self-clearing on-canvas notification stays up (within the 1–2s spec). */
        const val TRANSIENT_MESSAGE_MILLIS: Long = 1_500L

        // Page-turn swipe (FA-20): how far past which a release commits the turn, the slide-off /
        // snap-back animation durations, and the per-frame tick (~60fps).
        private const val PAGE_SWIPE_COMMIT_PX: Float = 140f
        private const val SWIPE_SLIDE_OFF_MS: Long = 160L
        private const val SWIPE_SNAP_BACK_MS: Long = 240L
        private const val SWIPE_FRAME_MS: Long = 16L

        // Vertical elastic page-turn (FA-20): how strongly an over-the-edge drag pulls the page, and how
        // far it must be pulled on release to turn the page (the vertical analog of PAGE_SWIPE_COMMIT_PX).
        private const val OVERSCROLL_DAMP: Float = 0.4f
        private const val PAGE_TURN_OVERSCROLL_PX: Float = 90f

        // ── Pinch zoom (FA-20) ──────────────────────────────────────────────────────────────
        /** Pinch-zoom range: 50%–400% of the fit-to-shorter-edge baseline. */
        const val MIN_ZOOM: Float = 0.5f
        const val MAX_ZOOM: Float = 4f
        /** A release within this fraction of a snap target (100% / fit-width) snaps to it. */
        private const val ZOOM_SNAP_THRESHOLD: Float = 0.07f
        /** Tolerance for the "currently on a snap" accent indicator. */
        private const val ZOOM_SNAP_EPSILON: Float = 0.01f


        /** Shown briefly when a re-triggered selection only holds already-captured items. */
        const val ALREADY_EXISTS_MESSAGE: String = "Already on your to-do list"

        /** Shown as red handwriting on the canvas for failures and timeouts. */
        const val CONNECTION_ERROR: String = "Could not connect — try again"

        /** Fallback body for an unclear response that carried no message line of its own. */
        private const val DEFAULT_UNCLEAR_MESSAGE: String = "I need more information, request unclear"

        /**
         * Captures the model's "Did you mean: …" clarification guess (one per unclear reply).
         * Tolerates a leading bullet/dash that stripMarkdown may have introduced.
         */
        private val DID_YOU_MEAN_REGEX = Regex("(?im)^[\\s•*-]*did you mean[:\\-]?\\s*(.+)$")

        /** Margin from the page edge for a full-line AI response box. */
        const val AI_NOTE_MARGIN_PX: Float = 32f

        /** Offset for a duplicated selection so the copy is visibly distinct from the original. */
        const val DUPLICATE_OFFSET_PX: Float = 24f

        /** Fallback y for a re-sent query when the error note's position is unknown. */
        private const val RESEND_FALLBACK_Y: Float = 120f

        /** Outlives the ViewModel for the onCleared() persistence flush. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Parses a due date to start-of-day epoch millis in the device zone. Accepts an
         * absolute ISO date ("2026-06-10") or a relative phrase ("this Monday", "tomorrow")
         * resolved against [today]; null if absent/unrecognised.
         */
        private fun String?.toEpochMillisOrNull(today: java.time.LocalDate): Long? {
            if (this.isNullOrBlank()) return null
            val date = ai.elrond.domain.RelativeDateResolver.resolve(this, today)
                ?: runCatching { java.time.LocalDate.parse(this.trim()) }.getOrNull()
                ?: return null
            return date.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        /** Markdown markers occasionally slip through despite the system prompt. */
        private fun String.stripMarkdown(): String =
            replace("**", "")
                .replace(Regex("(?m)^#+\\s*"), "")
                .replace(Regex("(?m)^[-*]\\s+"), "• ")

        private val SYSTEM_PROMPT = """
            You are the embedded AI assistant in Project Elrond, a handwriting
            note-taking app. The user wrote a question by hand on their note page
            and triggered you with /Q. The text comes from handwriting recognition,
            so tolerate small recognition errors. When page context is provided,
            use it only if the question refers to it. Answer concisely — your
            response is written back onto the note page, so prefer short, direct
            answers. Respond in plain text only: no markdown, no asterisks, no
            headings.

            Be strict and honest about clarity. Answer directly ONLY when you are
            confident you understand the request. Do NOT guess at an answer when the
            request is ambiguous, garbled, or missing detail, and do NOT hedge by
            answering one interpretation while noting it might be wrong.

            This is a one-shot answer, not a conversation: never ask the user a
            follow-up question and never offer to continue. When the request is
            unclear or you need more detail, reply with EXACTLY two lines and nothing
            else — first the literal sentence:
            I need more information, request unclear
            then, on the next line, your single most likely interpretation as a
            complete, self-contained question:
            Did you mean: <your best guess at the full question>?
            The "Did you mean" line MUST be a clear, complete question that you are
            confident you could answer directly if the user confirmed it — never
            propose a guess that you yourself would also find unclear, ambiguous, or
            unanswerable. If you cannot form such an answerable guess, output only the
            first line and omit the "Did you mean" line entirely.

            ${AssistantCapabilities.systemPromptSection()}
        """.trimIndent()

        /**
         * The full `/Q` system prompt for the chosen [unitSystem]. The stable [SYSTEM_PROMPT]
         * stays the cached prefix; the unit directive is appended at the end so prompt-caching is
         * unaffected as long as the unit setting doesn't change.
         */
        private fun systemPrompt(unitSystem: UnitSystem): String =
            "$SYSTEM_PROMPT\n\n${unitsDirective(unitSystem)}"

        private fun unitsDirective(unitSystem: UnitSystem): String = when (unitSystem) {
            UnitSystem.METRIC ->
                "When your answer includes a measurement, always express it using the metric " +
                    "system (SI units: metres, centimetres, kilograms, grams, litres, °C), " +
                    "converting from any other system if needed. Never add measurements the user " +
                    "did not ask for."
            UnitSystem.IMPERIAL ->
                "When your answer includes a measurement, always express it using the imperial/US " +
                    "system (feet, inches, miles, pounds, ounces, gallons, °F), converting from " +
                    "any other system if needed. Never add measurements the user did not ask for."
        }
    }
}

