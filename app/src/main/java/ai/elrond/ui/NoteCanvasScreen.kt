package ai.elrond.ui

import ai.elrond.ElrondApplication
import ai.elrond.ai.AiUiState
import ai.elrond.canvas.CanvasTool
import ai.elrond.canvas.CanvasViewModel
import ai.elrond.canvas.InkCanvas
import ai.elrond.canvas.canvasViewModelFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Note page screen: full-bleed ink canvas with a floating tool bar. */
@Composable
fun NoteCanvasScreen(
    pageId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as ElrondApplication
    // Keyed by pageId so each note gets its own ViewModel (and saved state).
    val viewModel: CanvasViewModel = viewModel(
        key = pageId,
        factory = canvasViewModelFactory(app.noteRepository, pageId),
    )
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()
    val aiState by viewModel.aiState.collectAsStateWithLifecycle()
    val aiNotes by viewModel.aiNotes.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
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
                    onResize = { delta -> viewModel.resizeAiNote(note.id, delta) },
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
