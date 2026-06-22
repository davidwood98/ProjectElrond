package ai.elrond.ui

import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * FA-14 workflow-status label + dot colour, shared by the editor to-do panel and the Library
 * to-do board (List + Kanban) so the status pills read identically everywhere.
 */
internal val TodoStatusStyle: Map<TodoStatus, Pair<String, Color>> = mapOf(
    TodoStatus.TODO to ("To-do" to Color(0xFFA9ABAC)),
    TodoStatus.IN_PROGRESS to ("In progress" to Color(0xFF4652A3)),
    TodoStatus.DONE to ("Done" to Color(0xFF3CB078)),
)

/**
 * Priority-dot colour, shared by the editor to-do panel and the Library board (FA-15). The dot sits
 * just under the checkbox in both places.
 */
internal val TodoPriorityColors: Map<TodoPriority, Color> = mapOf(
    TodoPriority.HIGH to Color(0xFFD32F2F),
    TodoPriority.MEDIUM to Color(0xFFF57C00),
    TodoPriority.LOW to Color(0xFF388E3C),
    TodoPriority.NONE to Color(0xFFC9CBCC),
)

private val TODO_DUE_FORMAT = DateTimeFormatter.ofPattern("d MMM")

/** Relative due-date label matching the design ("Today" / "Tomorrow" / "11 Feb"). */
internal fun todoDueLabel(millis: Long): String {
    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (date) {
        LocalDate.now() -> "Today"
        LocalDate.now().plusDays(1) -> "Tomorrow"
        LocalDate.now().minusDays(1) -> "Yesterday"
        else -> TODO_DUE_FORMAT.format(date)
    }
}

/** Priority dot (tap → priority menu) — placed just under the done tick box (FA-15). */
@Composable
internal fun TodoPriorityDot(current: TodoPriority, onSetPriority: (TodoPriority) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .size(12.dp)
                .clip(CircleShape)
                .background(TodoPriorityColors[current] ?: TodoPriorityColors.getValue(TodoPriority.NONE))
                .clickable { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TodoPriority.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    leadingIcon = { Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(TodoPriorityColors.getValue(p))) },
                    onClick = { onSetPriority(p); open = false },
                )
            }
        }
    }
}

/** Status indicator (dot + label, tap → status menu) — move between To-do / In progress / Done. */
@Composable
internal fun TodoStatusPill(current: TodoStatus, onSetStatus: (TodoStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (label, color) = TodoStatusStyle.getValue(current)
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { open = true }.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TodoStatus.entries.forEach { s ->
                val (l, c) = TodoStatusStyle.getValue(s)
                DropdownMenuItem(
                    text = { Text(l) },
                    leadingIcon = { Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(c)) },
                    onClick = { onSetStatus(s); open = false },
                )
            }
        }
    }
}

/**
 * Manual add-task row (text field + add button) — shared by the canvas to-do panel and the main
 * to-do menu so adding a task looks/behaves identically in both. Adds with no priority.
 */
@Composable
internal fun TodoAddRow(onAdd: (String, TodoPriority) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) { onAdd(text, TodoPriority.NONE); text = "" }
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("Add a task") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardActions = KeyboardActions(onDone = { submit() }),
        )
        IconButton(onClick = { submit() }, enabled = text.isNotBlank()) {
            Icon(Icons.Filled.Add, contentDescription = "Add task")
        }
    }
}
