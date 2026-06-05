package ai.elrond.canvas

import ai.elrond.BuildConfig
import ai.elrond.ai.AiInkNote
import ai.elrond.ai.AiUiState
import ai.elrond.ai.HandwritingRecognizer
import ai.elrond.ai.MlKitHandwritingRecognizer
import ai.elrond.ai.NotePosition
import ai.elrond.ai.QueryTriggerDetector
import ai.elrond.ai.defaultAiNotePosition
import ai.elrond.ai.groupStrokesIntoLines
import ai.elrond.aibackend.AIInput
import ai.elrond.aibackend.AIProvider
import ai.elrond.aibackend.AIRequest
import ai.elrond.aibackend.AiTaskExtractor
import ai.elrond.aibackend.AssistantCapabilities
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.aibackend.anthropic.AnthropicConfig
import ai.elrond.aibackend.anthropic.AnthropicProvider
import ai.elrond.data.NoteRepository
import ai.elrond.data.TodoRepository
import ai.elrond.todo.PendingTaskExtraction
import ai.elrond.todo.TodoPriority
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
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
class CanvasViewModel(
    private val recognizer: HandwritingRecognizer? = null,
    private val aiProvider: AIProvider? = null,
    private val lineSplitter: (List<Stroke>) -> List<List<Stroke>> = ::groupStrokesIntoLines,
    private val notePlacer: (List<Stroke>) -> NotePosition = ::defaultAiNotePosition,
    private val repository: NoteRepository? = null,
    private val taskExtractor: TaskExtractor? = null,
    private val todoRepository: TodoRepository? = null,
    private val pageId: String? = null,
    triggerCommandFlow: Flow<String>? = null,
    private val triggerDebounceMillis: Long = TRIGGER_DEBOUNCE_MILLIS,
    private val autoSaveDebounceMillis: Long = AUTOSAVE_DEBOUNCE_MILLIS,
    private val requestTimeoutMillis: Long = REQUEST_TIMEOUT_MILLIS,
) : ViewModel() {

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

    /** Tasks the AI extracted from the page, awaiting the user's confirmation to save. */
    private val _pendingExtraction = MutableStateFlow<PendingTaskExtraction?>(null)
    val pendingExtraction: StateFlow<PendingTaskExtraction?> = _pendingExtraction.asStateFlow()

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

    init {
        triggerCommandFlow?.let { flow ->
            viewModelScope.launch { flow.collect { triggerCommand = it } }
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
                lastPersistedAiNotes = _aiNotes.value
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

    @OptIn(FlowPreview::class)
    private fun startAutoSave(repository: NoteRepository, pageId: String) {
        viewModelScope.launch {
            _finishedStrokes.debounce(autoSaveDebounceMillis).collect { strokes ->
                if (strokes != lastPersisted) {
                    runCatching { repository.replaceStrokes(pageId, strokes) }
                        .onSuccess { lastPersisted = strokes }
                }
            }
        }
        viewModelScope.launch {
            _aiNotes.debounce(autoSaveDebounceMillis).collect { notes ->
                if (notes != lastPersistedAiNotes) {
                    runCatching { repository.replaceAiNotes(pageId, notes) }
                        .onSuccess { lastPersistedAiNotes = notes }
                }
            }
        }
    }

    override fun onCleared() {
        // Final flush so a quick back-press inside the debounce window isn't lost.
        val repository = repository
        val pageId = pageId
        val strokes = _finishedStrokes.value
        val aiNotes = _aiNotes.value
        if (repository != null && pageId != null) {
            if (strokes != lastPersisted) {
                flushScope.launch { runCatching { repository.replaceStrokes(pageId, strokes) } }
            }
            if (aiNotes != lastPersistedAiNotes) {
                flushScope.launch { runCatching { repository.replaceAiNotes(pageId, aiNotes) } }
            }
        }
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
        _tool.value = tool
    }

    fun setStylusOnly(enabled: Boolean) {
        _stylusOnly.value = enabled
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
            notes.map { if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it }
        }
    }

    /** Free resize: width and height change independently (aspect ratio unlocked). */
    fun resizeAiNote(id: String, dWidth: Float, dHeight: Float) {
        _aiNotes.update { notes ->
            notes.map { note ->
                if (note.id == id) {
                    val newWidth = (note.widthPx + dWidth).coerceAtLeast(AiInkNote.MIN_WIDTH_PX)
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

        // Cheap per-debounce check: only the line containing the last-drawn stroke.
        val lastStroke = strokes.last()
        val triggerIndex = lines.indexOfFirst { line -> line.any { it === lastStroke } }
            .takeIf { it >= 0 } ?: lines.lastIndex
        val triggerLine = lines[triggerIndex]
        val trigger = triggerCommand
        val triggerText = recognizer.recognize(triggerLine).getOrNull() ?: return
        if (!QueryTriggerDetector.containsTrigger(triggerText, trigger)) return

        // Question: text before the trigger on its line, else the line directly above.
        val inlinePrompt = QueryTriggerDetector.extractPrompt(triggerText, trigger)
        val questionLineIndex = if (inlinePrompt == null && triggerIndex > 0) triggerIndex - 1 else null
        val question = inlinePrompt
            ?: questionLineIndex?.let { recognizer.recognize(lines[it]).getOrNull()?.trim() }

        // Everything else on the page goes along as context.
        val context = lines.indices
            .filter { it != triggerIndex && it != questionLineIndex }
            .mapNotNull { recognizer.recognize(lines[it]).getOrNull()?.trim()?.ifEmpty { null } }
            .joinToString("\n")

        val effectiveQuestion = question?.ifBlank { null } ?: context.ifBlank { null } ?: return
        if (effectiveQuestion == lastHandledPrompt) return
        lastHandledPrompt = effectiveQuestion

        val provider = aiProvider
        if (provider == null) {
            _aiState.value = AiUiState.Error(
                "Anthropic API key not configured. Add anthropic.apiKey=sk-ant-... " +
                    "to local.properties and rebuild.",
            )
            return
        }

        val userPrompt = if (question == null || context.isBlank()) {
            effectiveQuestion
        } else {
            "Handwritten question: $effectiveQuestion\n\n" +
                "Other notes on the page (context, may be unrelated):\n$context"
        }

        val position = notePlacer(triggerLine)
        _aiState.value = AiUiState.Thinking(effectiveQuestion, position.x, position.y)

        // Extract-first: if the page holds NEW action items, capture them as a to-do
        // (confirmation sheet) and DO NOT write a /Q answer onto the canvas. Only a
        // genuine question (no new tasks) produces a rendered answer.
        if (offerExtraction(pageText = userPrompt)) {
            _aiState.value = AiUiState.Idle
            return
        }

        // 15s timeout: null result is treated like a connection failure.
        val result = withTimeoutOrNull(requestTimeoutMillis) {
            provider.generate(AIRequest(input = AIInput.Text(userPrompt), systemPrompt = SYSTEM_PROMPT))
        }
        when {
            result == null -> _aiState.value = AiUiState.Error(CONNECTION_ERROR, position.x, position.y)
            result.isSuccess -> {
                _aiNotes.update {
                    it + AiInkNote(
                        id = UUID.randomUUID().toString(),
                        text = result.getOrThrow().text.stripMarkdown(),
                        x = position.x,
                        y = position.y,
                        widthPx = defaultNoteWidth(),
                    )
                }
                _aiState.value = AiUiState.Idle
            }
            else -> _aiState.value = AiUiState.Error(CONNECTION_ERROR, position.x, position.y)
        }
    }

    /**
     * If the page contains NEW action items (ones not already on the to-do list),
     * raises the confirmation sheet and returns true so the caller suppresses the
     * `/Q` answer. Returns false when there is no extractor/repo, no tasks, or all
     * detected tasks already exist (self-assessed and ignored).
     *
     * Independent of `/Q` itself, so a future background save-job can reuse it.
     */
    private suspend fun offerExtraction(pageText: String): Boolean {
        val extractor = taskExtractor ?: return false
        val todoRepository = todoRepository ?: return false
        val pageId = pageId ?: return false
        if (pageText.isBlank()) return false

        val extracted = extractor.extract(pageText).getOrNull().orEmpty()
        if (extracted.isEmpty()) return false

        val existing = runCatching { todoRepository.existingContents() }.getOrDefault(emptySet())
        val newTasks = extracted.filter { it.content.trim().lowercase() !in existing }
        if (newTasks.isEmpty()) return false // all already captured — ignore

        val repoTasks = newTasks.map { task ->
            TodoRepository.ExtractedTask(
                content = task.content,
                priority = TodoPriority.entries.getOrElse(task.priority) { TodoPriority.NONE },
                dueAt = task.dueDateIso.toEpochMillisOrNull(),
            )
        }
        val title = repository?.getPage(pageId)?.displayTitle() ?: "Note"
        pendingRepoTasks = repoTasks
        pendingSourceTitle = title
        _pendingExtraction.value = PendingTaskExtraction(
            tasks = repoTasks.map { it.content },
            sourcePageTitle = title,
        )
        return true
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

        /** Shown as red handwriting on the canvas for failures and timeouts. */
        const val CONNECTION_ERROR: String = "Could not connect — try again"

        /** Margin from the page edge for a full-line AI response box. */
        const val AI_NOTE_MARGIN_PX: Float = 32f

        /** Outlives the ViewModel for the onCleared() persistence flush. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Parses an ISO date ("2026-06-10") to start-of-day epoch millis; null if absent/invalid. */
        private fun String?.toEpochMillisOrNull(): Long? {
            if (this.isNullOrBlank()) return null
            return runCatching {
                java.time.LocalDate.parse(this)
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
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

            This is a one-shot answer, not a conversation: never ask the user a
            follow-up question and never offer to continue. If the request is
            unclear or you need more detail to answer, reply with exactly this
            sentence and nothing else: "I need more information, request unclear".

            ${AssistantCapabilities.systemPromptSection()}
        """.trimIndent()

    }
}

/** Production wiring: ML Kit recognition + Anthropic backend + Room persistence + TODO extraction. */
fun canvasViewModelFactory(
    repository: NoteRepository,
    todoRepository: TodoRepository,
    settingsRepository: ai.elrond.settings.SettingsRepository,
    pageId: String,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        val provider = apiKey.takeIf { it.isNotBlank() }
            ?.let { AnthropicProvider(AnthropicConfig(apiKey = it)) }
        CanvasViewModel(
            recognizer = MlKitHandwritingRecognizer(),
            aiProvider = provider,
            taskExtractor = provider?.let { AiTaskExtractor(it) },
            todoRepository = todoRepository,
            repository = repository,
            pageId = pageId,
            triggerCommandFlow = settingsRepository.triggerCommand,
        )
    }
}
