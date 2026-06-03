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
import ai.elrond.aibackend.anthropic.AnthropicConfig
import ai.elrond.aibackend.anthropic.AnthropicProvider
import ai.elrond.data.NoteRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    private val pageId: String? = null,
    private val triggerDebounceMillis: Long = TRIGGER_DEBOUNCE_MILLIS,
    private val autoSaveDebounceMillis: Long = AUTOSAVE_DEBOUNCE_MILLIS,
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

    init {
        // Pre-download the handwriting model so the first /Q is fast.
        recognizer?.let { viewModelScope.launch { it.warmUp() } }

        if (repository != null && pageId != null) {
            viewModelScope.launch {
                runCatching { repository.loadStrokes(pageId) }
                    .onSuccess { loaded ->
                        if (loaded.isNotEmpty()) _finishedStrokes.value = loaded
                    }
                lastPersisted = _finishedStrokes.value
                startAutoSave(repository, pageId)
            }
        }
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
    }

    override fun onCleared() {
        // Final flush so a quick back-press inside the debounce window isn't lost.
        val repository = repository
        val pageId = pageId
        val strokes = _finishedStrokes.value
        if (repository != null && pageId != null && strokes != lastPersisted) {
            flushScope.launch {
                runCatching { repository.replaceStrokes(pageId, strokes) }
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

    fun resizeAiNote(id: String, scaleDelta: Float) {
        _aiNotes.update { notes ->
            notes.map { note ->
                if (note.id == id) {
                    note.copy(
                        scale = (note.scale + scaleDelta)
                            .coerceIn(AiInkNote.MIN_SCALE, AiInkNote.MAX_SCALE),
                    )
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
        val triggerText = recognizer.recognize(triggerLine).getOrNull() ?: return
        if (!QueryTriggerDetector.containsTrigger(triggerText)) return

        // Question: text before /Q on the trigger line, else the line directly above.
        val inlinePrompt = QueryTriggerDetector.extractPrompt(triggerText)
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

        _aiState.value = AiUiState.Thinking(effectiveQuestion)
        val result = provider.generate(
            AIRequest(input = AIInput.Text(userPrompt), systemPrompt = SYSTEM_PROMPT),
        )
        result.fold(
            onSuccess = { response ->
                val position = notePlacer(triggerLine)
                _aiNotes.update {
                    it + AiInkNote(
                        id = UUID.randomUUID().toString(),
                        text = response.text.stripMarkdown(),
                        x = position.x,
                        y = position.y,
                    )
                }
                _aiState.value = AiUiState.Idle
            },
            onFailure = {
                _aiState.value = AiUiState.Error(it.message ?: "AI request failed")
            },
        )
    }

    companion object {
        /** Dark ink for user strokes; AI note ink uses a distinct colour (see ui). */
        const val USER_INK_COLOR: Int = 0xFF1A237E.toInt()
        const val DEFAULT_BRUSH_SIZE: Float = 4f
        const val BRUSH_EPSILON: Float = 0.1f
        const val ERASER_RADIUS: Float = 16f
        const val TRIGGER_DEBOUNCE_MILLIS: Long = 900L
        const val AUTOSAVE_DEBOUNCE_MILLIS: Long = 800L
        const val MAX_HISTORY: Int = 50

        /** Outlives the ViewModel for the onCleared() persistence flush. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
        """.trimIndent()

    }
}

/** Production wiring: ML Kit recognition + Anthropic backend + Room persistence. */
fun canvasViewModelFactory(
    repository: NoteRepository,
    pageId: String,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val apiKey = BuildConfig.ANTHROPIC_API_KEY
        CanvasViewModel(
            recognizer = MlKitHandwritingRecognizer(),
            aiProvider = apiKey.takeIf { it.isNotBlank() }
                ?.let { AnthropicProvider(AnthropicConfig(apiKey = it)) },
            repository = repository,
            pageId = pageId,
        )
    }
}
