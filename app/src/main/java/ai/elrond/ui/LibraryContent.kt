package ai.elrond.ui

import ai.elrond.data.CalendarProviderType
import ai.elrond.domain.NotePage
import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoStatus
import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.TodoViewModel
import ai.elrond.ui.theme.LeapGreen
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapPink
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Library top-level tabs of the Notes section (FA-14). Favorites/Unfiled are placeholders. */
private enum class NotesTab(val label: String) {
    ALL("All Notes"), RECENTS("Recents"), TIMELINE("Timeline"), FAVORITES("Favorites"), UNFILED("Unfiled")
}

private const val RECENTS_COUNT = 6
private val NOTE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")
private val TODO_DUE = DateTimeFormatter.ofPattern("d MMM")

internal const val LIBRARY_EMPTY_TAG = "library-empty"
internal const val LIBRARY_NOTE_CARD_TAG = "library-note-card"

// ─────────────────────────────────── Notes ───────────────────────────────────

@Composable
fun NotesSection(
    onOpenNote: (String) -> Unit,
    noteListViewModel: NoteListViewModel,
    calendarViewModel: CalendarViewModel,
    eventsViewModel: EventsViewModel,
) {
    val notes by noteListViewModel.pages.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(NotesTab.ALL) }
    var deleteCandidate by remember { mutableStateOf<NotePage?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Search + import + account row (search/import are placeholders).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text("Search notes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(
                modifier = Modifier.size(38.dp).clip(CircleShape).background(LeapPink),
                contentAlignment = Alignment.Center,
            ) {
                Text("DW", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }

        // Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NotesTab.entries.forEach { t ->
                FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
            }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

        when (tab) {
            NotesTab.TIMELINE -> CalendarScreen(
                onOpenNote = onOpenNote,
                showEvents = false,
                viewModel = calendarViewModel,
                eventsViewModel = eventsViewModel,
            )
            NotesTab.FAVORITES -> PlaceholderState(
                "Favourites are coming soon",
                "Star a note to pin it here — coming with the next update.",
            )
            else -> {
                val shown = when (tab) {
                    NotesTab.RECENTS -> notes.take(RECENTS_COUNT)
                    else -> notes // ALL + UNFILED (everything is unfiled until subjects exist)
                }
                if (shown.isEmpty()) {
                    PlaceholderState(
                        "No notes yet",
                        "Tap + to create your first note. Write with your S Pen, and /Q to ask the AI.",
                        modifier = Modifier.testTag(LIBRARY_EMPTY_TAG),
                    )
                } else {
                    if (tab == NotesTab.UNFILED) {
                        Text(
                            "All notes are unfiled until subjects are available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 20.dp, top = 8.dp),
                        )
                    }
                    NotesGrid(
                        notes = shown,
                        noteListViewModel = noteListViewModel,
                        onOpenNote = onOpenNote,
                        onLongPress = { deleteCandidate = it },
                    )
                }
            }
        }
    }

    deleteCandidate?.let { page ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("Delete this note?") },
            text = { Text(page.displayTitle()) },
            confirmButton = {
                TextButton(onClick = {
                    noteListViewModel.deleteNote(page.id)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesGrid(
    notes: List<NotePage>,
    noteListViewModel: NoteListViewModel,
    onOpenNote: (String) -> Unit,
    onLongPress: (NotePage) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(notes, key = { it.id }) { page ->
            Card(
                modifier = Modifier
                    .testTag(LIBRARY_NOTE_CARD_TAG)
                    .combinedClickable(
                        onClick = { onOpenNote(page.id) },
                        onLongClick = { onLongPress(page) },
                    ),
            ) {
                Box {
                    NoteThumbnail(
                        page = page,
                        viewModel = noteListViewModel,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                    // Favourite star — placeholder (no backend yet).
                    Icon(
                        Icons.Outlined.StarBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp),
                    )
                }
                Column(modifier = Modifier.padding(13.dp)) {
                    Text(
                        page.displayTitle(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        NOTE_DATE.format(Instant.ofEpochMilli(page.modifiedAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────── Files ───────────────────────────────────

@Composable
fun FilesSection() {
    PlaceholderState(
        "Files are coming soon",
        "Import PDFs and reference documents alongside your notes. Not available yet.",
    )
}

// ─────────────────────────────────── Calendar ───────────────────────────────────

private data class ProviderMeta(val title: String, val desc: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun CalendarConnectSection(
    settingsViewModel: SettingsViewModel,
    eventsViewModel: EventsViewModel,
) {
    val selected by settingsViewModel.calendarProvider.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Connect a calendar so meetings and deadlines appear here. This reads your account only " +
                "— it never changes your notes.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val meta = mapOf(
            CalendarProviderType.DEVICE to ProviderMeta("Device calendar", "This tablet's calendar", Icons.Outlined.PhoneAndroid),
            CalendarProviderType.GOOGLE to ProviderMeta("Google Calendar", "Not yet available", Icons.Outlined.Email),
            CalendarProviderType.OUTLOOK to ProviderMeta("Microsoft Outlook", "Office 365 calendar", Icons.Outlined.Email),
        )
        CalendarProviderType.entries.forEach { type ->
            val m = meta.getValue(type)
            ProviderCard(
                meta = m,
                selected = selected == type,
                onSelect = { settingsViewModel.setCalendarProvider(type) },
            )
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        Text("Upcoming", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = LeapGrey)
        // The real events list + Outlook sign-in (reused from the calendar screen).
        Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
            EventsTab(eventsViewModel)
        }
    }
}

@Composable
private fun ProviderCard(meta: ProviderMeta, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(meta.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(meta.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(meta.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = LeapGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Selected", style = MaterialTheme.typography.labelMedium, color = LeapGreen)
                }
            } else {
                OutlinedButton(onClick = onSelect) { Text("Use") }
            }
        }
    }
}

// ─────────────────────────────────── To-do ───────────────────────────────────

@Composable
fun TodoBoardSection(
    todoViewModel: TodoViewModel,
    onOpenNote: (String) -> Unit,
) {
    val items by todoViewModel.items.collectAsStateWithLifecycle()
    var kanban by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = !kanban, onClick = { kanban = false }, label = { Text("List") })
            FilterChip(selected = kanban, onClick = { kanban = true }, label = { Text("Kanban") })
            Spacer(Modifier.weight(1f))
            Text(
                "Tasks from your notes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            PlaceholderState("No tasks yet", "Write notes and use /Q, or add tasks from a note's to-do panel.")
        } else if (kanban) {
            KanbanBoard(items = items, onSetStatus = todoViewModel::setStatus, onOpenNote = onOpenNote)
        } else {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                items.forEach { item ->
                    TodoListRow(
                        item = item,
                        onToggle = { todoViewModel.setCompleted(item.id, it) },
                        onSetStatus = { todoViewModel.setStatus(item.id, it) },
                        onDelete = { todoViewModel.delete(item.id) },
                        onOpenNote = { item.sourcePageId?.let(onOpenNote) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TodoListRow(
    item: TodoItem,
    onToggle: (Boolean) -> Unit,
    onSetStatus: (TodoStatus) -> Unit,
    onDelete: () -> Unit,
    onOpenNote: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = item.isCompleted, onCheckedChange = onToggle)
            Column(modifier = Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                    color = if (item.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(item.status, onSetStatus)
                    if (item.isAiExtracted && item.hasSourceLink) {
                        Text(
                            item.sourcePageTitle?.let { "from $it ↗" } ?: "AI",
                            style = MaterialTheme.typography.labelMedium,
                            color = LeapPink,
                            modifier = Modifier.clickable(onClick = onOpenNote),
                        )
                    }
                    item.dueAt?.let {
                        Text(
                            "Due " + TODO_DUE.format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            androidx.compose.material3.IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete task", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KanbanBoard(
    items: List<TodoItem>,
    onSetStatus: (String, TodoStatus) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TodoStatus.entries.forEach { status ->
            val (label, color) = TodoStatusStyle.getValue(status)
            val colItems = items.filter { it.status == status }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                    Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text(colItems.size.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                colItems.forEach { item ->
                    KanbanCard(item = item, onSetStatus = { onSetStatus(item.id, it) }, onOpenNote = { item.sourcePageId?.let(onOpenNote) })
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun KanbanCard(item: TodoItem, onSetStatus: (TodoStatus) -> Unit, onOpenNote: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Text(item.content, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (item.isAiExtracted && item.hasSourceLink) {
                    Text(
                        item.sourcePageTitle?.let { "from $it ↗" } ?: "AI",
                        style = MaterialTheme.typography.labelSmall,
                        color = LeapPink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).clickable(onClick = onOpenNote),
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                MoveMenu(current = item.status, onSetStatus = onSetStatus)
            }
        }
    }
}

/** A status pill (chip + dropdown) — sets the workflow status (To-do / In progress / Done). */
@Composable
private fun StatusPill(current: TodoStatus, onSetStatus: (TodoStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val (label, color) = TodoStatusStyle.getValue(current)
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(label) },
            leadingIcon = { Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color)) },
        )
        StatusDropdown(open = open, onDismiss = { open = false }, onSetStatus = onSetStatus)
    }
}

@Composable
private fun MoveMenu(current: TodoStatus, onSetStatus: (TodoStatus) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Move", modifier = Modifier.size(18.dp))
        }
        StatusDropdown(open = open, onDismiss = { open = false }, onSetStatus = onSetStatus)
    }
}

@Composable
private fun StatusDropdown(open: Boolean, onDismiss: () -> Unit, onSetStatus: (TodoStatus) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        TodoStatus.entries.forEach { s ->
            val (label, color) = TodoStatusStyle.getValue(s)
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color)) },
                onClick = { onSetStatus(s); onDismiss() },
            )
        }
    }
}

// ─────────────────────────────────── shared ───────────────────────────────────

@Composable
private fun PlaceholderState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = LeapGrey)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
