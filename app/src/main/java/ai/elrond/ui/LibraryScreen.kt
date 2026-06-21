package ai.elrond.ui

import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.TodoViewModel
import ai.elrond.ui.theme.LeapGrey
import ai.elrond.ui.theme.LeapPink
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/** Left-nav destinations of the Library (FA-14). Files is a placeholder; the rest are live. */
enum class LibraryNav(val label: String, val icon: ImageVector) {
    NOTES("Notes", Icons.Outlined.Description),
    FILES("Files", Icons.Outlined.Folder),
    CALENDAR("Calendar", Icons.Outlined.CalendarMonth),
    TODO("To-do", Icons.Outlined.Checklist),
}

/** Breakpoint: at/above this width the sidebar is persistent (landscape); below it collapses. */
private val SIDEBAR_BREAKPOINT = 720.dp

/**
 * The Library home (FA-14 "Canvas" handoff): a left sidebar (Notes / Files / Calendar / To-do +
 * Subjects + Settings) and a main content area. Responsive — the sidebar is a fixed rail in
 * landscape and a slide-out drawer in portrait. Replaces the old bottom-nav `HomeScreen`.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
        val wide = maxWidth >= SIDEBAR_BREAKPOINT
        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                Surface(
                    modifier = Modifier.width(272.dp).fillMaxHeight(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                ) { sidebar {} }
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    LibraryMain(
                        nav = nav,
                        showMenu = false,
                        onMenu = {},
                        onOpenNote = onOpenNote,
                        noteListViewModel = noteListViewModel,
                        todoViewModel = todoViewModel,
                        settingsViewModel = settingsViewModel,
                        calendarViewModel = calendarViewModel,
                        eventsViewModel = eventsViewModel,
                    )
                }
            }
        } else {
            val drawerState = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(272.dp)) {
                        sidebar { scope.launch { drawerState.close() } }
                    }
                },
            ) {
                LibraryMain(
                    nav = nav,
                    showMenu = true,
                    onMenu = { scope.launch { drawerState.open() } },
                    onOpenNote = onOpenNote,
                    noteListViewModel = noteListViewModel,
                    todoViewModel = todoViewModel,
                    settingsViewModel = settingsViewModel,
                    calendarViewModel = calendarViewModel,
                    eventsViewModel = eventsViewModel,
                )
            }
        }
    }
}

/** The main content area: a top bar (with the portrait menu button), the section, and the FAB. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryMain(
    nav: LibraryNav,
    showMenu: Boolean,
    onMenu: () -> Unit,
    onOpenNote: (String) -> Unit,
    noteListViewModel: NoteListViewModel,
    todoViewModel: TodoViewModel,
    settingsViewModel: SettingsViewModel,
    calendarViewModel: CalendarViewModel,
    eventsViewModel: EventsViewModel,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(nav.label) },
                navigationIcon = {
                    if (showMenu) {
                        IconButton(onClick = onMenu) {
                            Icon(Icons.Filled.Menu, contentDescription = "Menu")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (nav == LibraryNav.NOTES) {
                FloatingActionButton(onClick = { noteListViewModel.createNote(onOpenNote) }) {
                    Icon(Icons.Filled.Add, contentDescription = "New note")
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (nav) {
                LibraryNav.NOTES -> NotesSection(
                    onOpenNote = onOpenNote,
                    noteListViewModel = noteListViewModel,
                    calendarViewModel = calendarViewModel,
                    eventsViewModel = eventsViewModel,
                )
                LibraryNav.FILES -> FilesSection()
                LibraryNav.CALENDAR -> CalendarConnectSection(
                    settingsViewModel = settingsViewModel,
                    eventsViewModel = eventsViewModel,
                )
                LibraryNav.TODO -> TodoBoardSection(
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
            Image(
                painter = painterResource(ai.elrond.R.drawable.leap_mark),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(9.dp))
            Text(
                "Leap Notes",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = LeapGrey,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
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
        Icon(item.icon, contentDescription = null, tint = fg)
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
