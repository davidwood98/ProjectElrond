package ai.elrond.ui

import ai.elrond.data.CalendarProviderType
import ai.elrond.domain.NotePage
import ai.elrond.domain.NotebookSummary
import ai.elrond.domain.SubjectTree
import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoStatus
import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.SubjectViewModel
import ai.elrond.presentation.TagViewModel
import ai.elrond.presentation.TodoViewModel
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.theme.LeapGreen
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapPink
import ai.elrond.ui.theme.Neutral300
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** Library top-level tabs of the Notes section (FA-14). Favorites/Unfiled are placeholders. */
private enum class NotesTab(val label: String) {
    ALL("All Notes"), RECENTS("Recents"), TIMELINE("Timeline"), FAVORITES("Favourites"), UNFILED("Unfiled")
}

private val NOTE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy")

internal const val LIBRARY_EMPTY_TAG = "library-empty"
internal const val LIBRARY_NOTE_CARD_TAG = "library-note-card"

// ─────────────────────────────── shared top action row ───────────────────────────────

/**
 * The Library top action row (FA-15): an optional sidebar-toggle chevron (portrait only), a search
 * field, an import button, a "sort by" / view-options button, and the account avatar — which maps to
 * Settings for now. Search / import / sort are visual placeholders (no backend yet). Replaces the
 * old page-title app bar; content sits directly beneath it.
 */
@Composable
private fun LibraryActionBar(
    onToggleSidebar: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    // FA-24c: a non-null [query] makes the search field editable and filters the tile grid (Notes
    // section only). Null keeps the old static placeholder for the sections without search yet.
    query: String? = null,
    onQueryChange: (String) -> Unit = {},
    // Instruction text shown when the field is empty — varies by tab to signal the search scope.
    searchPlaceholder: String = "Search notes",
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (onToggleSidebar != null) {
            Surface(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).clickable(onClick = onToggleSidebar),
                shape = RoundedCornerShape(11.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ChevronRight, contentDescription = "Open menu", tint = LeapGrey, modifier = Modifier.size(22.dp))
                }
            }
        }
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, contentDescription = null, tint = Neutral500, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                if (query == null) {
                    Text(searchPlaceholder, color = Neutral500)
                } else {
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = LocalTextStyle.current.copy(color = LeapGrey),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text(searchPlaceholder, color = Neutral500)
                            inner()
                        },
                    )
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Clear search",
                            tint = Neutral500,
                            modifier = Modifier.size(18.dp).clickable { onQueryChange("") },
                        )
                    }
                }
            }
        }
        // Import (placeholder) — soft-accent tile, matching the handoff.
        Surface(
            modifier = Modifier.size(42.dp),
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(ElrondIcons.Import),
                    contentDescription = "Import (coming soon)",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Box(
            modifier = Modifier.size(38.dp).clip(CircleShape).background(LeapPink).clickable(onClick = onOpenSettings),
            contentAlignment = Alignment.Center,
        ) {
            Text("DW", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Sort / view-options button (placeholder) — sits at the right end of the tab row, per the handoff. */
@Composable
private fun ViewOptionsButton() {
    Surface(
        modifier = Modifier.size(34.dp),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(ElrondIcons.MoreVert),
                contentDescription = "Sort by (coming soon)",
                tint = LeapGrey,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ─────────────────────────────────── Notes ───────────────────────────────────

/** Per-card actions, bundled to keep [NotesGrid]/[NotebookCardItem] signatures small (FA-16). */
private data class NoteCardCallbacks(
    val onOpenNote: (String) -> Unit,
    val onRename: (NotebookSummary) -> Unit,
    val onMove: (NotebookSummary) -> Unit,
    /** Long-press the card OR the ⋮ Delete item — both open the delete confirmation. */
    val onDelete: (NotebookSummary) -> Unit,
    val onTapSubject: (String) -> Unit,
    /** Opens the shared tag picker for this notebook (FA-24). */
    val onTags: (NotebookSummary) -> Unit,
)

@Composable
fun NotesSection(
    onToggleSidebar: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    onOpenNote: (String) -> Unit,
    noteListViewModel: NoteListViewModel,
    calendarViewModel: CalendarViewModel,
    eventsViewModel: EventsViewModel,
    subjectViewModel: SubjectViewModel,
    tagViewModel: TagViewModel,
) {
    val notebooks by noteListViewModel.notebooks.collectAsStateWithLifecycle()
    val recents by noteListViewModel.recentNotebooks.collectAsStateWithLifecycle()
    val subjectsById by subjectViewModel.subjectsById.collectAsStateWithLifecycle()
    val noteSubjects by subjectViewModel.noteSubjects.collectAsStateWithLifecycle()
    val selectedSubjectId by subjectViewModel.selectedSubjectId.collectAsStateWithLifecycle()
    val selectedPath by subjectViewModel.selectedPath.collectAsStateWithLifecycle()
    val subjectTree by subjectViewModel.tree.collectAsStateWithLifecycle()
    // Explicit shared key (NOT the auto code-position key): the portrait and landscape layouts place
    // this section at different composition positions, so an auto-keyed rememberSaveable would store a
    // separate tab per orientation (the Activity recreates on rotation). A fixed key makes both
    // orientations read/write the one slot, so the current tab persists across rotation.
    var tab by rememberSaveable(key = "library.notesTab") { mutableStateOf(NotesTab.ALL) }
    var deleteCandidate by remember { mutableStateOf<NotebookSummary?>(null) }
    var renameCandidate by remember { mutableStateOf<NotebookSummary?>(null) }
    var assignCandidate by remember { mutableStateOf<NotebookSummary?>(null) }
    // FA-24: the notebook whose tag picker is open (mirrors the other dialog-candidate states).
    var tagCandidate by remember { mutableStateOf<NotebookSummary?>(null) }
    // FA-24d: picker lists existing tags most-used-first (manual add promotes the most common).
    val tagsByFrequency by tagViewModel.tagsByFrequency.collectAsStateWithLifecycle()
    val notebookTags by tagViewModel.notebookTags.collectAsStateWithLifecycle()

    val callbacks = NoteCardCallbacks(
        onOpenNote = onOpenNote,
        onRename = { renameCandidate = it },
        onMove = { assignCandidate = it },
        onDelete = { deleteCandidate = it },
        onTapSubject = { subjectViewModel.selectSubject(it) },
        onTags = {
            tagViewModel.pruneOrphans() // the menu must never list an orphaned tag
            tagCandidate = it
        },
    )

    var query by rememberSaveable(key = "library.searchQuery") { mutableStateOf("") }
    var matchingIds by remember { mutableStateOf<List<String>?>(null) }
    val notebooksById = remember(notebooks) { notebooks.associateBy { it.notebookId } }

    // FA-24c: landing on the library home ends any on-canvas search-result mode — so returning here
    // (or relaunching after a swipe-kill, which starts at the library) clears the editor highlights.
    LaunchedEffect(Unit) { noteListViewModel.clearSearchMode() }

    // FA-24c: debounced tile-filter search. Scope is computed in-memory from the active tab/subject
    // (the subject case expands to the whole tree via SubjectTree — broader than the direct-children
    // grid); SearchRepository returns the ranked notebook ids. Blank query → no filter (null).
    LaunchedEffect(query, tab, selectedSubjectId, notebooks, noteSubjects, subjectsById, recents) {
        val q = query.trim()
        if (q.isEmpty()) {
            matchingIds = null
            return@LaunchedEffect
        }
        delay(180) // debounce: the effect restarts (cancels) on each keystroke/scope change
        val scopeIds: Set<String> = when {
            selectedSubjectId != null -> {
                val subtree = SubjectTree.descendantsOf(
                    SubjectTree.rootAncestorId(selectedSubjectId, subjectsById), subjectsById,
                )
                notebooks.filter { noteSubjects[it.notebookId] in subtree }.mapTo(HashSet()) { it.notebookId }
            }
            tab == NotesTab.RECENTS -> recents.mapTo(HashSet()) { it.notebookId }
            tab == NotesTab.UNFILED -> notebooks.filter { it.notebookId !in noteSubjects }.mapTo(HashSet()) { it.notebookId }
            tab == NotesTab.FAVORITES -> emptySet() // no backing yet (placeholder tab)
            else -> notebooks.mapTo(HashSet()) { it.notebookId } // ALL + TIMELINE = entire library
        }
        matchingIds = noteListViewModel.searchNotebooks(q, scopeIds, notebooks.map { it.notebookId })
    }

    // FA-24c: the placeholder names the search scope so tab-scoped search reads clearly. A subject
    // view (breadcrumb shown, no tab) keeps the default; All/Timeline search the whole library.
    val searchPlaceholder = when {
        selectedSubjectId != null -> "Search notes"
        tab == NotesTab.RECENTS -> "Search recents"
        tab == NotesTab.FAVORITES -> "Search favourites"
        tab == NotesTab.UNFILED -> "Search unfiled"
        else -> "Search notes"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryActionBar(
            onToggleSidebar = onToggleSidebar,
            onOpenSettings = onOpenSettings,
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = searchPlaceholder,
        )

        // Header (subject breadcrumb or tab row) stays visible while searching — for context + exit.
        if (selectedSubjectId != null) {
            SubjectPathTabs(
                path = selectedPath,
                onSelectAll = { subjectViewModel.selectSubject(null) },
                onSelectSubject = { subjectViewModel.selectSubject(it) },
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp),
            )
            HorizontalDivider(modifier = Modifier.padding(top = 10.dp))
        } else {
            UnderlineTabRow(
                tabs = NotesTab.entries,
                selected = tab,
                label = { it.label },
                onSelect = { tab = it },
                trailing = { ViewOptionsButton() },
            )
        }

        val ids = matchingIds
        when {
            ids != null -> {
                // Search active: ranked matches (scope already applied) replace the tab body — even on
                // Timeline/Favorites, which search the whole library.
                val displayed = ids.mapNotNull { notebooksById[it] }
                if (displayed.isEmpty()) {
                    PlaceholderState("No results", "No notes match “${query.trim()}”.")
                } else {
                    // Opening a result puts its notebook into on-canvas search-result mode (the editor
                    // only shows the pill/highlights when there are content matches). The opened page id
                    // is a notebook's last-viewed/cover page → resolve which notebook was tapped.
                    val searchCallbacks = callbacks.copy(
                        onOpenNote = { pid ->
                            displayed.firstOrNull { it.lastViewedPageId == pid || it.coverPageId == pid }
                                ?.let { noteListViewModel.enterSearchMode(it.notebookId, query.trim()) }
                            callbacks.onOpenNote(pid)
                        },
                    )
                    NotesGrid(displayed, noteListViewModel, subjectsById, noteSubjects, searchCallbacks)
                }
            }
            selectedSubjectId != null -> {
                // Subject view: the grid shows only the notes filed directly in this subject.
                val shown = notebooks.filter { noteSubjects[it.notebookId] == selectedSubjectId }
                if (shown.isEmpty()) {
                    PlaceholderState(
                        "No notes in this subject yet",
                        "Move notes here from a note card's ⋮ menu, or open a note and assign it to this subject.",
                    )
                } else {
                    NotesGrid(shown, noteListViewModel, subjectsById, noteSubjects, callbacks)
                }
            }
            else -> when (tab) {
                NotesTab.TIMELINE -> CalendarScreen(
                    onOpenNote = onOpenNote,
                    showEvents = false,
                    viewModel = calendarViewModel,
                    eventsViewModel = eventsViewModel,
                    noteListViewModel = noteListViewModel,
                )
                NotesTab.FAVORITES -> PlaceholderState(
                    "Favourites are coming soon",
                    "Star a note to pin it here — coming with the next update.",
                )
                else -> {
                    // RECENTS = opened in the last 24h; UNFILED = notes with no subject; ALL = everything.
                    val shown = when (tab) {
                        NotesTab.RECENTS -> recents
                        NotesTab.UNFILED -> notebooks.filter { it.notebookId !in noteSubjects }
                        else -> notebooks
                    }
                    if (shown.isEmpty()) {
                        when (tab) {
                            NotesTab.RECENTS -> PlaceholderState(
                                "Nothing recent",
                                "Notes you open show up here for 24 hours, most recent first.",
                            )
                            NotesTab.UNFILED -> PlaceholderState(
                                "Nothing unfiled",
                                "Every note is filed into a subject. Notes with no subject show up here.",
                            )
                            else -> PlaceholderState(
                                "No notes yet",
                                "Tap the new-note button to start. Write with your S Pen, and /Q to ask the AI.",
                                modifier = Modifier.testTag(LIBRARY_EMPTY_TAG),
                            )
                        }
                    } else {
                        NotesGrid(shown, noteListViewModel, subjectsById, noteSubjects, callbacks)
                    }
                }
            }
        }
    }

    deleteCandidate?.let { notebook ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(if (notebook.pageCount > 1) "Delete this notebook?" else "Delete this note?") },
            text = {
                Text(
                    if (notebook.pageCount > 1) "${notebook.title} — all ${notebook.pageCount} pages"
                    else notebook.title,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    noteListViewModel.deleteNotebook(notebook.notebookId, notebook.coverPageId)
                    deleteCandidate = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("Cancel") } },
        )
    }
    renameCandidate?.let { notebook ->
        SubjectNameDialog(
            title = "Rename note",
            initial = notebook.title,
            confirmLabel = "Save",
            // The title is a notebook property (survives page reorders) — rename the notebook.
            onConfirm = { noteListViewModel.renameNote(notebook.notebookId, it); renameCandidate = null },
            onDismiss = { renameCandidate = null },
        )
    }
    assignCandidate?.let { notebook ->
        SubjectPickerDialog(
            tree = subjectTree,
            currentSubjectId = noteSubjects[notebook.notebookId],
            onPick = { subjectViewModel.assignNote(notebook.notebookId, it); assignCandidate = null },
            onDismiss = { assignCandidate = null },
        )
    }
    tagCandidate?.let { notebook ->
        val assignedIds = notebookTags[notebook.notebookId].orEmpty().map { it.id }.toSet()
        val tagSuggestions by tagViewModel.suggestionsFor(notebook.notebookId).collectAsStateWithLifecycle()
        TagPickerDialog(
            allTags = tagsByFrequency,
            assignedTagIds = assignedIds,
            onToggle = { tag ->
                if (tag.id in assignedIds) tagViewModel.removeTag(notebook.notebookId, tag.id, tag.name)
                else tagViewModel.assignTag(notebook.notebookId, tag.id)
            },
            onCreateAndAssign = { tagViewModel.createAndAssignTag(notebook.notebookId, it) },
            onDismiss = { tagCandidate = null },
            suggestions = tagSuggestions,
            onAcceptSuggestion = { tagViewModel.acceptSuggestion(notebook.notebookId, it) },
        )
    }
}

/**
 * Underline tab row (FA-15): bold label + accent underline when selected, no chip border/fill.
 * [trailing] (optional) is pinned to the right of the row, bottom-aligned with the tabs — used for
 * the view-options / "sort by" button, per the handoff (right of the tab row, not the search bar).
 */
@Composable
private fun <T> UnderlineTabRow(
    tabs: Iterable<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(26.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            tabs.forEach { t ->
                val isSelected = t == selected
                // The underline is drawn with drawBehind (NOT a fillMaxWidth Box): in a horizontalScroll
                // Row children get an unbounded width constraint, so fillMaxWidth collapses to 0 and the
                // underline vanished. drawBehind paints a 2.5dp accent line spanning exactly the text
                // width, 10dp below it — matching the handoff's `inset 0 -2px var(--acc)`.
                Text(
                    label(t),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else Neutral500,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = { onSelect(t) },
                        )
                        .padding(bottom = 10.dp)
                        .drawBehind {
                            if (isSelected) {
                                val sw = 2.5.dp.toPx()
                                drawLine(
                                    color = accent,
                                    start = Offset(0f, size.height - sw / 2f),
                                    end = Offset(size.width, size.height - sw / 2f),
                                    strokeWidth = sw,
                                    cap = StrokeCap.Round,
                                )
                            }
                        },
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            Box(modifier = Modifier.padding(bottom = 4.dp)) { trailing() }
        }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotesGrid(
    notebooks: List<NotebookSummary>,
    noteListViewModel: NoteListViewModel,
    subjectsById: Map<String, ai.elrond.domain.Subject>,
    noteSubjects: Map<String, String>,
    callbacks: NoteCardCallbacks,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(notebooks, key = { it.notebookId }) { notebook ->
            NotebookCardItem(
                notebook = notebook,
                noteListViewModel = noteListViewModel,
                subjectId = noteSubjects[notebook.notebookId],
                subjectsById = subjectsById,
                callbacks = callbacks,
            )
        }
    }
}

/**
 * A single notebook card (FA-20) — cover-page thumbnail, a page-count badge when it has more than one
 * page, title (the cover page's title) + date, a ⋮ menu (Rename / Move to subject / Delete) and the
 * subject breadcrumb. Tapping opens the notebook's most-recently-viewed page; long-press deletes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NotebookCardItem(
    notebook: NotebookSummary,
    noteListViewModel: NoteListViewModel,
    subjectId: String?,
    subjectsById: Map<String, ai.elrond.domain.Subject>,
    callbacks: NoteCardCallbacks,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
            .testTag(LIBRARY_NOTE_CARD_TAG)
            .combinedClickable(
                onClick = { callbacks.onOpenNote(notebook.lastViewedPageId) },
                onLongClick = { callbacks.onDelete(notebook) },
            ),
    ) {
        Column {
            Box {
                NoteThumbnail(
                    pageId = notebook.coverPageId,
                    modifiedAt = notebook.modifiedAt,
                    viewModel = noteListViewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                )
                if (notebook.pageCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f),
                        modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
                    ) {
                        Text(
                            "${notebook.pageCount} pages",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = LeapGrey,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
                // Favourite star — placeholder (no backend yet).
                Icon(
                    Icons.Outlined.StarBorder,
                    contentDescription = null,
                    tint = Neutral500,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp).size(20.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Column(modifier = Modifier.padding(13.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            notebook.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            NOTE_DATE.format(Instant.ofEpochMilli(notebook.modifiedAt).atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.bodySmall,
                            color = Neutral500,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Note actions", tint = Neutral500, modifier = Modifier.size(20.dp))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; callbacks.onRename(notebook) })
                            DropdownMenuItem(text = { Text("Move to subject") }, onClick = { menuOpen = false; callbacks.onMove(notebook) })
                            DropdownMenuItem(text = { Text("Tags") }, onClick = { menuOpen = false; callbacks.onTags(notebook) })
                            DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; callbacks.onDelete(notebook) })
                        }
                    }
                }
                if (subjectId != null) {
                    SubjectBreadcrumb(
                        subjectId = subjectId,
                        subjectsById = subjectsById,
                        onTapSubject = callbacks.onTapSubject,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────── Files ───────────────────────────────────

@Composable
fun FilesSection(
    onToggleSidebar: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        LibraryActionBar(onToggleSidebar = onToggleSidebar, onOpenSettings = onOpenSettings)
        PlaceholderState(
            "Files are coming soon",
            "Import PDFs and reference documents alongside your notes. Not available yet.",
        )
    }
}

// ─────────────────────────────────── Calendar ───────────────────────────────────

private data class ProviderMeta(val title: String, val desc: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun CalendarConnectSection(
    onToggleSidebar: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    settingsViewModel: SettingsViewModel,
    eventsViewModel: EventsViewModel,
) {
    val selected by settingsViewModel.calendarProvider.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize()) {
        LibraryActionBar(onToggleSidebar = onToggleSidebar, onOpenSettings = onOpenSettings)
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
            Box(modifier = Modifier.fillMaxWidth().height(360.dp)) {
                EventsTab(eventsViewModel)
            }
        }
    }
}

@Composable
private fun ProviderCard(meta: ProviderMeta, selected: Boolean, onSelect: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
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
    onToggleSidebar: (() -> Unit)?,
    onOpenSettings: () -> Unit,
    todoViewModel: TodoViewModel,
    onOpenNote: (String) -> Unit,
) {
    val items by todoViewModel.items.collectAsStateWithLifecycle()
    val sourceLabels by todoViewModel.sourceLabels.collectAsStateWithLifecycle()
    // Shared key so the List/Kanban choice persists across rotation (see NotesSection's note).
    var kanban by rememberSaveable(key = "library.todoKanban") { mutableStateOf(false) }
    var editingDueFor by remember { mutableStateOf<TodoItem?>(null) }
    var editingTitleFor by remember { mutableStateOf<TodoItem?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        LibraryActionBar(onToggleSidebar = onToggleSidebar, onOpenSettings = onOpenSettings)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SegmentedToggle(
                options = listOf("List" to !kanban, "Kanban" to kanban),
                onSelect = { kanban = it == "Kanban" },
            )
            Text(
                "Tasks added from your note canvas",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Content fills the space above the pinned add-task row.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                PlaceholderState("No tasks yet", "Write notes and use /Q, add a task below, or add tasks from a note's to-do panel.")
            } else if (kanban) {
                KanbanBoard(
                    items = items,
                    onSetStatus = todoViewModel::setStatus,
                    onSetPriority = { id, p, item -> todoViewModel.edit(id, item.content, p, item.dueAt) },
                    onEditDue = { editingDueFor = it },
                    onOpenNote = onOpenNote,
                    sourceLabels = sourceLabels,
                )
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                    items.forEach { item ->
                        TodoTile(
                            item = item,
                            onToggle = { todoViewModel.setCompleted(item.id, it) },
                            onSetPriority = { todoViewModel.edit(item.id, item.content, it, item.dueAt) },
                            onSetStatus = { todoViewModel.setStatus(item.id, it) },
                            onEditTitle = { editingTitleFor = item },
                            onEditDue = { editingDueFor = item },
                            onDelete = { todoViewModel.delete(item.id) },
                            onOpenSource = { item.sourcePageId?.let(onOpenNote) },
                            sourceLabel = item.sourcePageId?.let { sourceLabels[it] },
                            compact = false,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        // Manual add-task row pinned at the bottom (like the canvas to-do menu). The end inset clears
        // the lower-right New-note FAB.
        HorizontalDivider(modifier = Modifier.padding(horizontal = 20.dp))
        TodoAddRow(
            onAdd = todoViewModel::add,
            modifier = Modifier.padding(start = 20.dp, end = 76.dp, top = 8.dp, bottom = 16.dp),
        )
    }

    editingDueFor?.let { item ->
        DueDatePicker(
            item = item,
            onSet = { millis -> todoViewModel.edit(item.id, item.content, item.priority, millis); editingDueFor = null },
            onDismiss = { editingDueFor = null },
        )
    }

    editingTitleFor?.let { item ->
        SubjectNameDialog(
            title = "Edit task",
            initial = item.content,
            confirmLabel = "Save",
            onConfirm = { todoViewModel.edit(item.id, it, item.priority, item.dueAt); editingTitleFor = null },
            onDismiss = { editingTitleFor = null },
        )
    }
}

/** List / Kanban segmented toggle — a pill track with the active option in a white pill. */
@Composable
private fun SegmentedToggle(options: List<Pair<String, Boolean>>, onSelect: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEach { (label, active) ->
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (active) 1.dp else 0.dp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { onSelect(label) },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else Neutral500,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}


@Composable
private fun KanbanBoard(
    items: List<TodoItem>,
    onSetStatus: (String, TodoStatus) -> Unit,
    onSetPriority: (String, TodoPriority, TodoItem) -> Unit,
    onEditDue: (TodoItem) -> Unit,
    onOpenNote: (String) -> Unit,
    sourceLabels: Map<String, String> = emptyMap(),
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
                    KanbanCard(
                        item = item,
                        onSetStatus = { onSetStatus(item.id, it) },
                        onSetPriority = { onSetPriority(item.id, it, item) },
                        onEditDue = { onEditDue(item) },
                        onOpenNote = { item.sourcePageId?.let(onOpenNote) },
                        sourceLabel = item.sourcePageId?.let { sourceLabels[it] },
                    )
                    Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun KanbanCard(
    item: TodoItem,
    onSetStatus: (TodoStatus) -> Unit,
    onSetPriority: (TodoPriority) -> Unit,
    onEditDue: () -> Unit,
    onOpenNote: () -> Unit,
    sourceLabel: String? = null,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Neutral300),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null,
                    color = if (item.isCompleted) Neutral500 else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                // Kebab: move status + set priority (kanban keeps priority in the menu per spec).
                KanbanMenu(current = item.status, currentPriority = item.priority, onSetStatus = onSetStatus, onSetPriority = onSetPriority)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (item.isAiExtracted && item.hasSourceLink) {
                    AiSourceLink(
                        title = sourceLabel ?: item.sourcePageTitle.orEmpty(),
                        onClick = onOpenNote,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Text(
                    text = item.dueAt?.let { todoDueLabel(it) } ?: "Set date",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.dueAt != null) MaterialTheme.colorScheme.onSurfaceVariant else Neutral500,
                    modifier = Modifier.clickable(onClick = onEditDue),
                )
            }
        }
    }
}

@Composable
private fun KanbanMenu(
    current: TodoStatus,
    currentPriority: TodoPriority,
    onSetStatus: (TodoStatus) -> Unit,
    onSetPriority: (TodoPriority) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        androidx.compose.material3.IconButton(onClick = { open = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Move / priority", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Move to", style = MaterialTheme.typography.labelSmall, color = Neutral500, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            TodoStatus.entries.forEach { s ->
                val (label, color) = TodoStatusStyle.getValue(s)
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = { Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color)) },
                    onClick = { onSetStatus(s); open = false },
                )
            }
            HorizontalDivider()
            Text("Priority", style = MaterialTheme.typography.labelSmall, color = Neutral500, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            TodoPriority.entries.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    leadingIcon = { Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(TodoPriorityColors.getValue(p))) },
                    onClick = { onSetPriority(p); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePicker(item: TodoItem, onSet: (Long?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = item.dueAt)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onSet(state.selectedDateMillis) }) { Text("Set") } },
        dismissButton = {
            Row {
                if (item.dueAt != null) {
                    TextButton(onClick = { onSet(null) }) { Text("Clear") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) { DatePicker(state = state) }
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
