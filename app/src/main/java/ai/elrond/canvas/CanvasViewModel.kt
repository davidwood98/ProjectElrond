package ai.elrond.canvas

import ai.elrond.BuildConfig
import ai.elrond.ai.AiInkNote
import ai.elrond.ai.AiUiState
import ai.elrond.ai.GestureTriggerDetector
import ai.elrond.ai.HandwritingRecognizer
import ai.elrond.ai.MlKitHandwritingRecognizer
import ai.elrond.ai.NotePosition
import ai.elrond.ai.QueryTriggerDetector
import ai.elrond.ai.TriggerMode
import ai.elrond.ai.defaultAiNotePosition
import ai.elrond.ai.groupStrokesIntoLines
import ai.elrond.ai.selectQuestionLines
import ai.elrond.ai.strokeCentroid
import ai.elrond.ai.strokeLoopOrNull
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AiTaskExtractor
import ai.elrond.aibackend.AssistantCapabilities
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.aibackend.anthropic.AnthropicConfig
import ai.elrond.aibackend.anthropic.AnthropicProvider
import ai.elrond.calendar.CalendarEvent
import ai.elrond.data.CalendarRepository
import ai.elrond.data.NoteRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import ai.elrond.extract.ExtractionScheduler
import ai.elrond.extract.PendingSuggestion
import ai.elrond.extract.SuggestionType
import ai.elrond.settings.SettingsRepository
import ai.elrond.todo.PendingTaskExtraction
import ai.elrond.todo.TodoPriority
import android.content.Context
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.withTimeoutOrNull

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
    /** Centroid of a stroke, used to test lasso enclosure (gesture mode). */
    private val centroidOf: (Stroke) -> GestureTriggerDetector.Point = ::strokeCentroid,
    triggerModeFlow: Flow<TriggerMode>? = null,
    stylusOnlyFlow: Flow<Boolean>? = null,
    /** Write-through for the palm-rejection preference (null in tests). */
    private val persistStylusOnly: (suspend (Boolean) -> Unit)? = null,
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
        stylusOnlyFlow = settings.stylusOnly,
        persistStylusOnly = { settings.setStylusOnly(it) },
    )

    private val _finishedStrokes = MutableStateFlow<List<Stroke>>(emptyList())
    val finishedStrokes: StateFlow<List<Stroke>> = _finishedStrokes.asStateFlow()

    private val _tool = MutableStateFlow(CanvasTool.PEN)
    val tool: StateFlow<CanvasTool> = _tool.asStateFlow()

    /** Palm rejection: when true (default), finger touches never draw ink. */
    private val _stylusOnly = MutableStateFlow(true)
    val stylusOnly: StateFlow<Boolean> = _stylusOnly.asStateFlow()

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

    private val undoStack = ArrayDeque<List<Stroke>>()
    private val redoStack = ArrayDeque<List<Stroke>>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    /** Snapshot taken at eraser-gesture start so one gesture = one undo step. */
    private var eraseGestureSnapshot: List<Stroke>? = null

    private var triggerDetectionJob: Job? = null

    /** Last prompt already handled — prevents re-triggering on unchanged content. */
    private var lastHandledPrompt: String? = null

    /** Strokes as last persisted — avoids redundant writes and enables the close-flush. */
    private var lastPersisted: List<Stroke> = emptyList()

    /** AI notes as last persisted — same role for the ai_notes table. */
    private var lastPersistedAiNotes: List<AiInkNote> = emptyList()

    /** Latest canvas width in px, reported by the UI; sizes new full-line AI boxes. */
    private var canvasWidthPx: Float = 0f

    /** Current activation command (default `/Q`), kept in sync with settings. */
    @Volatile
    private var triggerCommand: String = QueryTriggerDetector.DEFAULT_TRIGGER

    /** Current activation method (written command vs lasso gesture), kept in sync with settings. */
    @Volatile
    private var triggerMode: TriggerMode = TriggerMode.COMMAND

    init {
        triggerCommandFlow?.let { flow ->
            viewModelScope.launch { flow.collect { triggerCommand = it } }
        }
        triggerModeFlow?.let { flow ->
            viewModelScope.launch { flow.collect { triggerMode = it } }
        }
        stylusOnlyFlow?.let { flow ->
            viewModelScope.launch { flow.collect { _stylusOnly.value = it } }
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
                lastPersisted = _finishedStrokes.value
                lastPersistedAiNotes = _aiNotes.value.filterNot { it.isError }
                startAutoSave(repository, pageId)
            }
        }
    }

    /** Reports the live canvas width so new AI boxes default to a full line. */
    fun setCanvasSize(widthPx: Float) {
        canvasWidthPx = widthPx
    }

    private fun defaultNoteWidth(): Float {
        val usable = canvasWidthPx - 2 * AI_NOTE_MARGIN_PX
        return if (usable > AiInkNote.MIN_WIDTH_PX) usable else AiInkNote.FALLBACK_WIDTH_PX
    }

    /**
     * Largest width a box whose left edge is at [x] can take while still leaving a margin at the
     * right page edge. Returns [Float.MAX_VALUE] when the canvas width isn't known yet (e.g. unit
     * tests), so geometry is only ever clamped once the real page width has been reported.
     */
    private fun maxWidthAt(x: Float): Float =
        if (canvasWidthPx > 0f) {
            (canvasWidthPx - x - AI_NOTE_MARGIN_PX).coerceAtLeast(AiInkNote.MIN_WIDTH_PX)
        } else {
            Float.MAX_VALUE
        }

    @OptIn(FlowPreview::class)
    private fun startAutoSave(repository: NoteRepository, pageId: String) {
        viewModelScope.launch {
            _finishedStrokes.debounce(autoSaveDebounceMillis).collect { strokes ->
                if (strokes != lastPersisted) {
                    runCatching { repository.replaceStrokes(pageId, strokes) }
                        .onSuccess {
                            lastPersisted = strokes
                            // Kick off background TODO/calendar extraction for the saved page.
                            if (strokes.isNotEmpty()) enqueueExtraction?.invoke(pageId)
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

    override fun onCleared() {
        // Final flush so a quick back-press inside the debounce window isn't lost.
        val repository = repository
        val pageId = pageId
        val strokes = _finishedStrokes.value
        val aiNotes = _aiNotes.value.filterNot { it.isError }
        if (repository != null && pageId != null) {
            if (strokes != lastPersisted) {
                flushScope.launch { runCatching { repository.replaceStrokes(pageId, strokes) } }
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
            family = StockBrushes.pressurePen(),
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
        persistStylusOnly?.let { writer -> viewModelScope.launch { writer(enabled) } }
    }

    /** Called by the canvas when wet strokes complete and become dry strokes. */
    fun onStrokesFinished(strokes: Collection<Stroke>) {
        if (strokes.isEmpty()) return
        pushUndoSnapshot(_finishedStrokes.value)
        _finishedStrokes.update { it + strokes }
        scheduleTriggerDetection()
    }

    /** Marks the start of an eraser gesture so all its removals undo as one step. */
    fun beginEraseGesture() {
        eraseGestureSnapshot = _finishedStrokes.value
    }

    /** Erase any stroke whose geometry intersects the eraser position. */
    fun eraseAt(x: Float, y: Float, radius: Float = ERASER_RADIUS) {
        val before = _finishedStrokes.value
        val eraserBox = ImmutableBox.fromCenterAndDimensions(
            ImmutableVec(x, y),
            radius * 2,
            radius * 2,
        )
        val after = before.filterNot { stroke ->
            stroke.shape.computeCoverageIsGreaterThan(eraserBox, 0f)
        }
        if (after.size == before.size) return

        val snapshot = eraseGestureSnapshot ?: before
        eraseGestureSnapshot = null // only one undo step per gesture
        pushUndoSnapshot(snapshot)
        _finishedStrokes.value = after
    }

    fun undo() {
        val snapshot = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(_finishedStrokes.value)
        _finishedStrokes.value = snapshot
        updateHistoryFlags()
    }

    fun redo() {
        val snapshot = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(_finishedStrokes.value)
        _finishedStrokes.value = snapshot
        updateHistoryFlags()
    }

    fun clearPage() {
        triggerDetectionJob?.cancel()
        if (_finishedStrokes.value.isNotEmpty()) {
            pushUndoSnapshot(_finishedStrokes.value)
        }
        _finishedStrokes.value = emptyList()
        _aiNotes.value = emptyList()
        _aiState.value = AiUiState.Idle
        lastHandledPrompt = null
    }

    fun dismissAiResponse() {
        _aiState.value = AiUiState.Idle
    }

    // --- AI note manipulation ---

    fun moveAiNote(id: String, dx: Float, dy: Float) {
        _aiNotes.update { notes ->
            notes.map { note ->
                if (note.id == id) {
                    // Keep the box on the page: never let it slide off the left/right edge or
                    // above the top. (Clamps only once the canvas width is known — see maxWidthAt.)
                    val maxX = if (canvasWidthPx > 0f) {
                        (canvasWidthPx - note.widthPx).coerceAtLeast(0f)
                    } else {
                        Float.MAX_VALUE
                    }
                    val newX = (note.x + dx).coerceIn(0f, maxX)
                    val newY = (note.y + dy).coerceAtLeast(0f)
                    note.copy(x = newX, y = newY)
                } else {
                    note
                }
            }
        }
    }

    /** Free resize: width and height change independently (aspect ratio unlocked). */
    fun resizeAiNote(id: String, dWidth: Float, dHeight: Float) {
        _aiNotes.update { notes ->
            notes.map { note ->
                if (note.id == id) {
                    // Cap the width so the box can't grow past the right page edge.
                    val newWidth = (note.widthPx + dWidth)
                        .coerceIn(AiInkNote.MIN_WIDTH_PX, maxWidthAt(note.x))
                    val currentHeight = note.heightPx ?: AiInkNote.MIN_HEIGHT_PX
                    val newHeight = (currentHeight + dHeight).coerceAtLeast(AiInkNote.MIN_HEIGHT_PX)
                    note.copy(widthPx = newWidth, heightPx = newHeight)
                } else {
                    note
                }
            }
        }
    }

    fun removeAiNote(id: String) {
        _aiNotes.update { notes -> notes.filterNot { it.id == id } }
    }

    // --- History internals ---

    private fun pushUndoSnapshot(snapshot: List<Stroke>) {
        undoStack.addLast(snapshot)
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
        val strokes = _finishedStrokes.value
        if (strokes.isEmpty()) return
        val lines = lineSplitter(strokes)
        if (lines.isEmpty()) return

        when (triggerMode) {
            TriggerMode.COMMAND -> handleCommandTrigger(recognizer, strokes, lines)
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
        _finishedStrokes.update { current -> current.filterNot { it === lassoStroke } }

        // A lasso is a deliberate, explicit request (like pressing a button): re-circling the
        // same selection must always re-run, so it bypasses the unchanged-content de-dupe guard.
        submitQuery(question, question, notePlacer(enclosed), bypassDedup = true)
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
            provider.generate(AIRequest(input = AIInput.Text(userPrompt), systemPrompt = SYSTEM_PROMPT))
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
                            suggestedQuestion = suggestedQuestion(answer),
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
        viewModelScope.launch { submitQuery(text, text, position) }
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
        /** Dark ink for user strokes; AI note ink uses a distinct colour (see ui). */
        const val USER_INK_COLOR: Int = 0xFF1A237E.toInt()
        const val DEFAULT_BRUSH_SIZE: Float = 4f
        const val BRUSH_EPSILON: Float = 0.1f
        const val ERASER_RADIUS: Float = 16f
        const val TRIGGER_DEBOUNCE_MILLIS: Long = 900L
        const val AUTOSAVE_DEBOUNCE_MILLIS: Long = 800L
        const val REQUEST_TIMEOUT_MILLIS: Long = 15_000L
        const val MAX_HISTORY: Int = 50

        /** How long a self-clearing on-canvas notification stays up (within the 1–2s spec). */
        const val TRANSIENT_MESSAGE_MILLIS: Long = 1_500L

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
            val date = ai.elrond.ai.RelativeDateResolver.resolve(this, today)
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
            If you genuinely cannot even guess what was meant, output only the first
            line and omit the "Did you mean" line.

            ${AssistantCapabilities.systemPromptSection()}
        """.trimIndent()

    }
}

