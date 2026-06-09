package ai.elrond.ui

import ai.elrond.ai.AiUiState
import ai.elrond.canvas.CanvasTool
import ai.elrond.canvas.CanvasViewModel
import ai.elrond.canvas.InkCanvas
import ai.elrond.extract.PendingSuggestion
import ai.elrond.extract.SuggestionType
import ai.elrond.settings.SettingsViewModel
import ai.elrond.todo.TodoViewModel
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Note page screen: full-bleed ink canvas with a floating tool bar. */
@Composable
fun NoteCanvasScreen(
    pageId: String,
    onBack: () -> Unit,
    onOpenNote: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CanvasViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val aiNotes by viewModel.aiNotes.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val pendingExtraction by viewModel.pendingExtraction.collectAsStateWithLifecycle()
    val todoCount by todoViewModel.activeCount.collectAsStateWithLifecycle()
    var showTodoPanel by remember { mutableStateOf(false) }
    val pendingSuggestions by viewModel.pendingSuggestions.collectAsStateWithLifecycle()
    val hasNewExtractedItems by settingsViewModel.hasNewExtractedItems.collectAsStateWithLifecycle()
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // AI-box selection lives in the UI (not persisted). When the "edit mode on creation"
    // setting is on (default) a freshly created note starts selected; loaded notes always
    // start deselected (part of the note flow). Deselect by tapping anywhere off the box.
    val selectOnCreate by settingsViewModel.aiNoteSelectedOnCreate.collectAsStateWithLifecycle()
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    // Auto-select only notes the user just created via /Q (a ViewModel event), never notes
    // loaded from storage — so a saved page opens with its AI notes deselected.
    LaunchedEffect(viewModel) {
        viewModel.createdNoteEvents.collect { id ->
            if (selectOnCreate) selectedNoteId = id
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                canvasSize = it
                viewModel.setCanvasSize(it.width.toFloat())
            },
    ) {
        InkCanvas(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )

        // Tap anywhere off a selected AI box to deselect it (place it into the note flow).
        // Present only while something is selected, so it never intercepts drawing otherwise.
        if (selectedNoteId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { selectedNoteId = null }
                    },
            )
        }

        // AI responses as handwriting-style ink, above the canvas layers.
        aiNotes.forEach { note ->
            key(note.id) {
                AiInkNoteView(
                    note = note,
                    selected = selectedNoteId == note.id,
                    onSelect = { selectedNoteId = note.id },
                    onMove = { dx, dy -> viewModel.moveAiNote(note.id, dx, dy) },
                    onResize = { dW, dH -> viewModel.resizeAiNote(note.id, dW, dH) },
                    onRemove = { viewModel.removeAiNote(note.id) },
                )
            }
        }

        // On-canvas AI activity: loading dots while thinking, red ink on failure.
        when (val state = aiState) {
            is AiUiState.Thinking -> AiLoadingIndicator(x = state.x, y = state.y)
            is AiUiState.Error -> AiErrorInk(
                message = state.message,
                x = state.x,
                y = state.y,
                onDismiss = viewModel::dismissAiResponse,
            )
            AiUiState.Idle -> Unit
        }

        // FA-2: on-canvas Yes/No popups for background-extracted items, near the detected text.
        pendingSuggestions.forEach { suggestion ->
            key(suggestion.id) {
                AutoExtractPopup(
                    suggestion = suggestion,
                    canvasSize = canvasSize,
                    onYes = { viewModel.acceptSuggestion(suggestion.id) },
                    onNo = { viewModel.rejectSuggestion(suggestion.id) },
                )
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to notes")
            }
        }

        // TODO list button with outstanding-count badge.
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            IconButton(onClick = {
                showTodoPanel = true
                // Opening the panel clears the "new items" flair.
                settingsViewModel.markExtractedItemsSeen()
            }) {
                BadgedBox(
                    badge = {
                        when {
                            hasNewExtractedItems -> Badge { Text("+") }
                            todoCount > 0 -> Badge { Text(todoCount.toString()) }
                        }
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "To-do list")
                }
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = tool == CanvasTool.PEN,
                    onClick = { viewModel.selectTool(CanvasTool.PEN) },
                    label = { Text("Pen") },
                )
                FilterChip(
                    selected = tool == CanvasTool.ERASER,
                    onClick = { viewModel.selectTool(CanvasTool.ERASER) },
                    label = { Text("Eraser") },
                )
                FilterChip(
                    selected = !stylusOnly,
                    onClick = { viewModel.setStylusOnly(!stylusOnly) },
                    label = { Text("Finger draw") },
                )
                TextButton(onClick = viewModel::undo, enabled = canUndo) {
                    Text("↶ Undo")
                }
                TextButton(onClick = viewModel::redo, enabled = canRedo) {
                    Text("↷ Redo")
                }
                TextButton(onClick = viewModel::clearPage) {
                    Text("Clear")
                }
            }
        }

        if (showTodoPanel) {
            TodoPanel(
                viewModel = todoViewModel,
                onDismiss = { showTodoPanel = false },
                onOpenSource = { sourceId ->
                    showTodoPanel = false
                    if (sourceId != pageId) onOpenNote(sourceId)
                },
            )
        }

        pendingExtraction?.let { pending ->
            TaskExtractionSheet(
                tasks = pending.tasks,
                onConfirm = viewModel::confirmExtraction,
                onDismiss = viewModel::dismissExtraction,
            )
        }
    }
}

/**
 * Confirmation bottom sheet for AI-extracted tasks, with a per-item toggle so the
 * user adds only the ones they want. Dismissing adds nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskExtractionSheet(
    tasks: List<String>,
    onConfirm: (Set<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    val checked = remember(tasks) {
        mutableStateListOf<Boolean>().apply { repeat(tasks.size) { add(true) } }
    }
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "AI found these action items — add to your to-do list?",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            tasks.forEachIndexed { index, task ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked[index],
                        onCheckedChange = { checked[index] = it },
                    )
                    Text(
                        text = task,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                FilledTonalButton(
                    onClick = {
                        onConfirm(checked.indices.filter { checked[it] }.toSet())
                    },
                    enabled = checked.any { it },
                ) {
                    Text("Add selected")
                }
            }
        }
    }
}

/** Animated "ink dots" loading indicator placed on the canvas where the answer will land. */
@Composable
private fun AiLoadingIndicator(x: Float, y: Float) {
    val density = LocalDensity.current
    val transition = rememberInfiniteTransition(label = "ai-loading")
    Row(
        modifier = Modifier
            .absoluteOffset(
                x = with(density) { x.toDp() },
                y = with(density) { y.toDp() },
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = i * 200),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(AiInkColor.copy(alpha = alpha)),
            )
        }
    }
}

/** Inline failure message in red handwriting style, on the canvas; tap to dismiss. */
@Composable
private fun AiErrorInk(message: String, x: Float, y: Float, onDismiss: () -> Unit) {
    val density = LocalDensity.current
    Text(
        text = message,
        fontFamily = HandwritingFontFamily,
        fontSize = 24.sp,
        color = Color(0xFFC62828),
        modifier = Modifier
            .absoluteOffset(
                x = with(density) { x.toDp() },
                y = with(density) { y.toDp() },
            )
            .clickable(onClick = onDismiss)
            .padding(4.dp),
    )
}

/**
 * On-canvas confirmation popup for a background-extracted item (FA-2). Anchored near the
 * detected text and clamped so it always stays fully within the visible canvas — if the
 * anchor is near an edge the popup shifts inward rather than clipping off screen.
 */
@Composable
private fun AutoExtractPopup(
    suggestion: PendingSuggestion,
    canvasSize: IntSize,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    val density = LocalDensity.current
    var popupSize by remember { mutableStateOf(IntSize.Zero) }
    val x = if (canvasSize.width > 0 && popupSize.width > 0) {
        suggestion.x.coerceIn(0f, (canvasSize.width - popupSize.width).toFloat().coerceAtLeast(0f))
    } else {
        suggestion.x
    }
    val topInsetPx = with(density) { 72.dp.toPx() }
    val y = if (canvasSize.height > 0 && popupSize.height > 0) {
        val maxY = (canvasSize.height - popupSize.height).toFloat().coerceAtLeast(0f)
        // Keep the popup below the top toolbar row, but never above the bottom bound.
        suggestion.y.coerceIn(0f, maxY).coerceAtLeast(minOf(topInsetPx, maxY))
    } else {
        suggestion.y
    }
    val label = if (suggestion.type == SuggestionType.TODO) "Add to To-Do" else "Create event"

    Surface(
        modifier = Modifier
            .absoluteOffset(
                x = with(density) { x.toDp() },
                y = with(density) { y.toDp() },
            )
            .onSizeChanged { popupSize = it },
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, AiInkColor.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = AiInkColor)
            Text(
                text = suggestion.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp).padding(top = 2.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onNo) { Text("No") }
                FilledTonalButton(onClick = onYes) { Text("Yes") }
            }
        }
    }
}
