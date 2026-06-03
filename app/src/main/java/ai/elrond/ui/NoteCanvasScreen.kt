package ai.elrond.ui

import ai.elrond.canvas.CanvasTool
import ai.elrond.canvas.CanvasViewModel
import ai.elrond.canvas.InkCanvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Note page screen: full-bleed ink canvas with a floating tool bar. */
@Composable
fun NoteCanvasScreen(
    modifier: Modifier = Modifier,
    viewModel: CanvasViewModel = viewModel(),
) {
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val stylusOnly by viewModel.stylusOnly.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize()) {
        InkCanvas(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
        )
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
                TextButton(onClick = viewModel::clearPage) {
                    Text("Clear")
                }
            }
        }
    }
}
