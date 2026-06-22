package ai.elrond.ui

import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.TodoViewModel
import ai.elrond.ui.icons.ElrondIcons
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapPink
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Left-nav destinations of the Library (FA-14). Files is a placeholder; the rest are live. */
enum class LibraryNav(val label: String, val icon: ImageVector) {
    NOTES("Notes", Icons.Outlined.Description),
    FILES("Files", Icons.Outlined.Folder),
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth),
    TODO("To-do", Icons.Outlined.Description), // To-do uses the bespoke checklist glyph (see NavRow)
}

private val SIDEBAR_WIDTH = 272.dp

/**
 * The Library home (FA-15 redesign to the Claude Design "Canvas"/portrait handoff): a left sidebar
 * (Elrond · Notes / Files / Calendar / To-do + Subjects + Settings) and a main content area.
 *
 * Responsive: in landscape the sidebar is a fixed rail; in portrait it is a slide-out drawer that
 * pushes off-screen to the left, leaving a rounded **pull-tab** handle peeking at the edge, toggled
 * by a chevron button in the content's top action row (per Canvas-Portrait.dc.html). Replaces the
 * old bottom-nav `HomeScreen`.
 */
@Composable
fun LibraryScreen(
    onOpenNote: (pageId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    noteListViewModel: NoteListViewModel = hiltViewModel(),
    todoViewModel: TodoViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    calendarViewModel: CalendarViewModel = hiltViewModel(),
    eventsViewModel: EventsViewModel = hiltViewModel(),
) {
    var nav by rememberSaveable { mutableStateOf(LibraryNav.NOTES) }
    val notes by noteListViewModel.pages.collectAsStateWithLifecycle()
    val todoCount by todoViewModel.activeCount.collectAsStateWithLifecycle()

    val sidebar: @Composable (onItem: () -> Unit) -> Unit = { onItem ->
        LibrarySidebar(
            current = nav,
            noteCount = notes.size,
            todoCount = todoCount,
            onSelect = { nav = it; onItem() },
            onOpenSettings = { onItem(); onOpenSettings() },
        )
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Orientation-driven, NOT width-driven: a tablet in portrait is still wide (~800dp), so a
        // width breakpoint kept the fixed rail in portrait. Landscape = persistent rail; portrait =
        // a slide-out drawer (pull-tab + chevron toggle), per Canvas-Portrait.dc.html.
        val landscape = maxWidth > maxHeight
        if (landscape) {
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) { sidebar {} }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    LibraryMain(
                        nav = nav,
                        onToggleSidebar = null,
                        onOpenNote = onOpenNote,
                        onOpenSettings = onOpenSettings,
                        noteListViewModel = noteListViewModel,
                        todoViewModel = todoViewModel,
                        settingsViewModel = settingsViewModel,
                        calendarViewModel = calendarViewModel,
                        eventsViewModel = eventsViewModel,
                    )
                }
            }
        } else {
            var sidebarOpen by rememberSaveable { mutableStateOf(false) }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // Secondary affordance: a right-swipe on the home content opens the drawer
                        // (the chevron beside the search bar is the primary button). Only active
                        // while closed, so it never fights the open drawer / scrim.
                        .pointerInput(sidebarOpen) {
                            if (!sidebarOpen) {
                                val threshold = 48.dp.toPx()
                                var dragX = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { dragX = 0f },
                                    onHorizontalDrag = { _, amount ->
                                        dragX += amount
                                        if (dragX > threshold) sidebarOpen = true
                                    },
                                )
                            }
                        },
                ) {
                    LibraryMain(
                        nav = nav,
                        onToggleSidebar = { sidebarOpen = !sidebarOpen },
                        onOpenNote = onOpenNote,
                        onOpenSettings = onOpenSettings,
                        noteListViewModel = noteListViewModel,
                        todoViewModel = todoViewModel,
                        settingsViewModel = settingsViewModel,
                        calendarViewModel = calendarViewModel,
                        eventsViewModel = eventsViewModel,
                    )
                }
                SlideOutSidebar(
                    open = sidebarOpen,
                    onScrimTap = { sidebarOpen = false },
                ) { sidebar { sidebarOpen = false } }
            }
        }
    }
}

/**
 * Portrait sidebar: an off-canvas drawer that translates fully off-screen (`-SIDEBAR_WIDTH`) when
 * closed and to 0 when open, with a tap-to-close scrim. There is **no** edge pull-tab — it is opened
 * by the chevron next to the search bar (primary) or a right-swipe on the home content (secondary),
 * matching the Canvas-Portrait handoff.
 */
@Composable
private fun SlideOutSidebar(
    open: Boolean,
    onScrimTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (open) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x47262626))
                .clickable(onClick = onScrimTap),
        )
    }
    val offsetX by animateDpAsState(if (open) 0.dp else -SIDEBAR_WIDTH, label = "sidebar-offset")
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(SIDEBAR_WIDTH)
            .offset(x = offsetX),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (open) 8.dp else 0.dp,
    ) { content() }
}

/** The main content area: the section (with its own top action row) + the New-note FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryMain(
    nav: LibraryNav,
    onToggleSidebar: (() -> Unit)?,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
    noteListViewModel: NoteListViewModel,
    todoViewModel: TodoViewModel,
    settingsViewModel: SettingsViewModel,
    calendarViewModel: CalendarViewModel,
    eventsViewModel: EventsViewModel,
) {
    Scaffold(
        // New note is always reachable from the lower-right, on every section (FA-15).
        floatingActionButton = {
            FloatingActionButton(
                onClick = { noteListViewModel.createNote(onOpenNote) },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    painterResource(ElrondIcons.NewNote),
                    contentDescription = "New note",
                    modifier = Modifier.size(30.dp),
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (nav) {
                LibraryNav.NOTES -> NotesSection(
                    onToggleSidebar = onToggleSidebar,
                    onOpenSettings = onOpenSettings,
                    onOpenNote = onOpenNote,
                    noteListViewModel = noteListViewModel,
                    calendarViewModel = calendarViewModel,
                    eventsViewModel = eventsViewModel,
                )
                LibraryNav.FILES -> FilesSection(
                    onToggleSidebar = onToggleSidebar,
                    onOpenSettings = onOpenSettings,
                )
                LibraryNav.CALENDAR -> CalendarConnectSection(
                    onToggleSidebar = onToggleSidebar,
                    onOpenSettings = onOpenSettings,
                    settingsViewModel = settingsViewModel,
                    eventsViewModel = eventsViewModel,
                )
                LibraryNav.TODO -> TodoBoardSection(
                    onToggleSidebar = onToggleSidebar,
                    onOpenSettings = onOpenSettings,
                    todoViewModel = todoViewModel,
                    onOpenNote = onOpenNote,
                )
            }
        }
    }
}

@Composable
private fun LibrarySidebar(
    current: LibraryNav,
    noteCount: Int,
    todoCount: Int,
    onSelect: (LibraryNav) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight().padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Placeholder project title (the Leap mark/wordmark is intentionally not shown yet).
            Text(
                "Elrond",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = LeapGrey,
                modifier = Modifier.weight(1f),
            )
            // Dark-mode toggle placeholder (a minimal sun) — no-op for now.
            IconButton(onClick = { /* TODO: dark mode */ }) {
                Icon(
                    Icons.Outlined.LightMode,
                    contentDescription = "Toggle dark mode",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LibraryNav.entries.forEach { item ->
            val count = when (item) {
                LibraryNav.NOTES -> noteCount
                LibraryNav.TODO -> todoCount
                else -> null
            }
            NavRow(item = item, selected = current == item, count = count, onClick = { onSelect(item) })
        }

        // Subjects — a UI placeholder (no backend yet).
        Text(
            "SUBJECTS",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 22.dp, bottom = 6.dp),
        )
        SubjectPlaceholder("Welcome", LeapPink)
        SubjectPlaceholder("Test structure", MaterialTheme.colorScheme.primary)
        SubjectPlaceholder("Personal", ai.elrond.ui.theme.LeapGreen)

        Spacer(Modifier.weight(1f))

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(11.dp),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.CloudDone,
                    contentDescription = null,
                    tint = ai.elrond.ui.theme.LeapGreen,
                    modifier = Modifier.size(17.dp),
                )
                Spacer(Modifier.width(9.dp))
                Text("All changes synced", style = MaterialTheme.typography.bodySmall, color = LeapGrey)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text("Settings", style = MaterialTheme.typography.bodyLarge, color = LeapGrey)
        }
    }
}

@Composable
private fun NavRow(item: LibraryNav, selected: Boolean, count: Int?, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else LeapGrey
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item == LibraryNav.TODO) {
            Icon(painterResource(ElrondIcons.Checklist), contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        } else {
            Icon(item.icon, contentDescription = null, tint = fg)
        }
        Spacer(Modifier.width(12.dp))
        Text(item.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, color = fg, modifier = Modifier.weight(1f))
        if (count != null && count > 0) {
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SubjectPlaceholder(name: String, dot: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(11.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(11.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, color = LeapGrey)
    }
}
