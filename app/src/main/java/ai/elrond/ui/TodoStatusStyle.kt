package ai.elrond.ui

import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoStatus
import ai.elrond.ui.theme.Neutral300
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
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

/**
 * The single to-do tile shared by BOTH the canvas [TodoPanel] and the Library to-do viewer's List
 * view, so its chrome + interactions (tap-title-to-rename, priority dot, due chip, delete, AI source
 * link) live in one place — a change here reaches both surfaces. [compact] selects the narrow-drawer
 * look (status shown by a light tile-fill wash, single-line ellipsised title, due chip inline under
 * the title) vs the wide-viewer look (a [TodoStatusPill] + a right-hand due column, wrapping title).
 * The Kanban card stays separate by design. Outer spacing between tiles is the caller's [modifier].
 */
@Composable
internal fun TodoTile(
    item: TodoItem,
    onToggle: (Boolean) -> Unit,
    onSetPriority: (TodoPriority) -> Unit,
    onSetStatus: (TodoStatus) -> Unit,
    onEditTitle: () -> Unit,
    onEditDue: () -> Unit,
    onDelete: () -> Unit,
    onOpenSource: () -> Unit,
    sourceLabel: String?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    // Compact drawer encodes in-progress by a light tile wash (no pill fits the narrow width); the
    // wide viewer shows an explicit status pill instead, so its tile stays plain.
    val tileColor = if (compact && item.status == TodoStatus.IN_PROGRESS) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Neutral300),
        color = tileColor,
    ) {
        Row(
            modifier = Modifier.padding(if (compact) 12.dp else 14.dp),
            verticalAlignment = if (compact) Alignment.CenterVertically else Alignment.Top,
        ) {
            // Checkbox with the priority dot directly beneath it (FA-15).
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
                TodoPriorityDot(item.priority, onSetPriority)
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp, top = if (compact) 0.dp else 10.dp)) {
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    // Compact: one line + ellipsis so every drawer tile is the same height. Wide: wraps.
                    maxLines = if (compact) 1 else Int.MAX_VALUE,
                    overflow = TextOverflow.Ellipsis,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                    color = if (item.isCompleted) Neutral500 else MaterialTheme.colorScheme.onSurface,
                    // Tap the title to rename the task, matching the notebook-title rename UX.
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onEditTitle),
                )
                if (compact) {
                    // Source link + due chip sit inline under the title in the narrow drawer.
                    Row(
                        modifier = Modifier.padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (item.isAiExtracted && item.hasSourceLink) {
                            AiSourceLink(
                                title = sourceLabel ?: item.sourcePageTitle.orEmpty(),
                                onClick = onOpenSource,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        TodoDueChip(item, onEditDue)
                    }
                } else if (item.isAiExtracted && item.hasSourceLink) {
                    AiSourceLink(
                        title = sourceLabel ?: item.sourcePageTitle.orEmpty(),
                        onClick = onOpenSource,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!compact) {
                // Wide viewer: status pill + due date stacked at the right edge.
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 8.dp)) {
                    TodoStatusPill(item.status, onSetStatus)
                    Spacer(Modifier.height(6.dp))
                    TodoDueChip(item, onEditDue)
                }
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

/** Tappable due-date chip ("Set date" when unset) — the due affordance for both tile layouts. */
@Composable
private fun TodoDueChip(item: TodoItem, onEditDue: () -> Unit) {
    Text(
        text = item.dueAt?.let { todoDueLabel(it) } ?: "Set date",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = if (item.dueAt != null) MaterialTheme.colorScheme.onSurfaceVariant else Neutral500,
        modifier = Modifier.clickable(onClick = onEditDue),
    )
}
