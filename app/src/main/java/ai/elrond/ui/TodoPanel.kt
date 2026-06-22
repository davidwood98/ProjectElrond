package ai.elrond.ui

import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoStatus
import ai.elrond.presentation.TodoViewModel
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextDecoration
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
    val active = items.filterNot { it.isCompleted }
    val done = items.filter { it.isCompleted }
    var editingDueFor by remember { mutableStateOf<TodoItem?>(null) }

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
                            TodoRow(
                                item = item,
                                onToggle = { checked -> viewModel.setCompleted(item.id, checked) },
                                onDelete = { viewModel.delete(item.id) },
                                onOpenSource = { item.sourcePageId?.let(onOpenSource) },
                                onEditDue = { editingDueFor = item },
                                onSetPriority = { p -> viewModel.edit(item.id, item.content, p, item.dueAt) },
                                onSetStatus = { s -> viewModel.setStatus(item.id, s) },
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
                                TodoRow(
                                    item = item,
                                    onToggle = { checked -> viewModel.setCompleted(item.id, checked) },
                                    onDelete = { viewModel.delete(item.id) },
                                    onOpenSource = { item.sourcePageId?.let(onOpenSource) },
                                    onEditDue = { editingDueFor = item },
                                    onSetPriority = { p -> viewModel.edit(item.id, item.content, p, item.dueAt) },
                                    onSetStatus = { s -> viewModel.setStatus(item.id, s) },
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                AddRow(onAdd = viewModel::add)
            }
        }
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

@Composable
private fun AddRow(onAdd: (String, TodoPriority) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add a task") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardActions = KeyboardActions(onDone = {
                onAdd(text, TodoPriority.NONE)
                text = ""
            }),
        )
        IconButton(
            onClick = {
                onAdd(text, TodoPriority.NONE)
                text = ""
            },
            enabled = text.isNotBlank(),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "Add task")
        }
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onOpenSource: () -> Unit,
    onEditDue: () -> Unit,
    onSetPriority: (TodoPriority) -> Unit,
    onSetStatus: (TodoStatus) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            // Checkbox with the priority dot directly beneath it (FA-15).
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
                TodoPriorityDot(item.priority, onSetPriority)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = 10.dp)) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                    color = if (item.isCompleted) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (item.isAiExtracted && item.hasSourceLink) {
                    // "from [note]" link, tappable while the source note still exists.
                    Text(
                        text = item.sourcePageTitle?.let { "🔗 $it" } ?: "AI",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp).clickable(onClick = onOpenSource),
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 8.dp)) {
                TodoStatusPill(item.status, onSetStatus)
                Spacer(Modifier.size(6.dp))
                Text(
                    text = item.dueAt?.let { todoDueLabel(it) } ?: "Set date",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (item.dueAt != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.clickable(onClick = onEditDue),
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete task",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
