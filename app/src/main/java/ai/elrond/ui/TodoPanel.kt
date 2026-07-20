package ai.elrond.ui

import ai.elrond.domain.TodoItem
import ai.elrond.presentation.TodoViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Floating TODO drawer anchored to the right edge, dismissed by tapping the scrim.
 * Active tasks sit at the top; completed tasks drop into a separate section below;
 * a manual-add field is pinned at the bottom. Tapping an item's source chip opens
 * its note via [onOpenSource].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoPanel(
    viewModel: TodoViewModel,
    onDismiss: () -> Unit,
    onOpenSource: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val sourceLabels by viewModel.sourceLabels.collectAsStateWithLifecycle()
    val active = items.filterNot { it.isCompleted }
    val done = items.filter { it.isCompleted }
    var editingDueFor by remember { mutableStateOf<TodoItem?>(null) }
    var editingTitleFor by remember { mutableStateOf<TodoItem?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onDismiss),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(380.dp),
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Text("To-do", style = MaterialTheme.typography.headlineSmall)
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                if (items.isEmpty()) {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No tasks yet.\nAdd one below, or write notes and use /Q — " +
                                "the AI will offer to add any action items.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(active, key = { it.id }) { item ->
                            TodoTile(
                                item = item,
                                onToggle = { checked -> viewModel.setCompleted(item.id, checked) },
                                onSetPriority = { p -> viewModel.edit(item.id, item.content, p, item.dueAt) },
                                onSetStatus = { s -> viewModel.setStatus(item.id, s) },
                                onEditTitle = { editingTitleFor = item },
                                onEditDue = { editingDueFor = item },
                                onDelete = { viewModel.delete(item.id) },
                                onOpenSource = { item.sourcePageId?.let(onOpenSource) },
                                sourceLabel = item.sourcePageId?.let { sourceLabels[it] },
                                compact = true,
                                modifier = Modifier.padding(vertical = 3.dp),
                            )
                        }
                        if (done.isNotEmpty()) {
                            item {
                                Text(
                                    "Done (${done.size})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(done, key = { it.id }) { item ->
                                TodoTile(
                                    item = item,
                                    onToggle = { checked -> viewModel.setCompleted(item.id, checked) },
                                    onSetPriority = { p -> viewModel.edit(item.id, item.content, p, item.dueAt) },
                                    onSetStatus = { s -> viewModel.setStatus(item.id, s) },
                                    onEditTitle = { editingTitleFor = item },
                                    onEditDue = { editingDueFor = item },
                                    onDelete = { viewModel.delete(item.id) },
                                    onOpenSource = { item.sourcePageId?.let(onOpenSource) },
                                    sourceLabel = item.sourcePageId?.let { sourceLabels[it] },
                                    compact = true,
                                    modifier = Modifier.padding(vertical = 3.dp),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                TodoAddRow(onAdd = viewModel::add)
            }
        }
    }

    editingTitleFor?.let { item ->
        SubjectNameDialog(
            title = "Edit task",
            initial = item.content,
            confirmLabel = "Save",
            onConfirm = { viewModel.edit(item.id, it, item.priority, item.dueAt); editingTitleFor = null },
            onDismiss = { editingTitleFor = null },
        )
    }

    editingDueFor?.let { item ->
        val state = rememberDatePickerState(initialSelectedDateMillis = item.dueAt)
        DatePickerDialog(
            onDismissRequest = { editingDueFor = null },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.edit(item.id, item.content, item.priority, state.selectedDateMillis)
                    editingDueFor = null
                }) { Text("Set") }
            },
            dismissButton = {
                Row {
                    if (item.dueAt != null) {
                        TextButton(onClick = {
                            viewModel.edit(item.id, item.content, item.priority, null)
                            editingDueFor = null
                        }) { Text("Clear") }
                    }
                    TextButton(onClick = { editingDueFor = null }) { Text("Cancel") }
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
