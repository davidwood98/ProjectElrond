package ai.elrond.ui

import ai.elrond.ElrondApplication
import ai.elrond.ai.AiUiState
import ai.elrond.canvas.CanvasTool
import ai.elrond.canvas.CanvasViewModel
import ai.elrond.canvas.InkCanvas
import ai.elrond.canvas.canvasViewModelFactory
import ai.elrond.todo.TodoViewModel
import ai.elrond.todo.todoViewModelFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Note page screen: full-bleed ink canvas with a floating tool bar. */
@Composable
fun NoteCanvasScreen(
    pageId: String,
    onBack: () -> Unit,
    onOpenNote: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as ElrondApplication
    // Keyed by pageId so each note gets its own ViewModel (and saved state).
    val viewModel: CanvasViewModel = viewModel(
        key = pageId,
        factory = canvasViewModelFactory(app.noteRepository, app.todoRepository, pageId),
    )
    val todoViewModel: TodoViewModel = viewModel(factory = todoViewModelFactory(app.todoRepository))
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val aiNotes by viewModel.aiNotes.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    val pendingExtraction by viewModel.pendingExtraction.collectAsStateWithLifecycle()
    val todoCount by todoViewModel.activeCount.collectAsStateWithLifecycle()
    var showTodoPanel by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewModel.setCanvasSize(it.width.toFloat()) },
    ) {
        InkCanvas(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )

        // AI responses as handwriting-style ink, above the canvas layers.
        aiNotes.forEach { note ->
            key(note.id) {
                AiInkNoteView(
                    note = note,
                    onMove = { dx, dy -> viewModel.moveAiNote(note.id, dx, dy) },
                    onResize = { dW, dH -> viewModel.resizeAiNote(note.id, dW, dH) },
                    onRemove = { viewModel.removeAiNote(note.id) },
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
            IconButton(onClick = { showTodoPanel = true }) {
                BadgedBox(
                    badge = {
                        if (todoCount > 0) Badge { Text(todoCount.toString()) }
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

        AiAssistantPanel(
            state = aiState,
            onDismiss = viewModel::dismissAiResponse,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        )

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

/** Transient assistant status: in-flight progress and errors. */
@Composable
private fun AiAssistantPanel(
    state: AiUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is AiUiState.Idle) return

    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is AiUiState.Thinking -> {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = "Thinking about: “${state.prompt}”",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is AiUiState.Error -> {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text("Dismiss")
                    }
                }

                AiUiState.Idle -> Unit
            }
        }
    }
}
