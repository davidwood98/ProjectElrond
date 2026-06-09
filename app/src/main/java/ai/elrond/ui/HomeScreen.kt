package ai.elrond.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

private enum class HomeTab { NOTES, CALENDAR }

/**
 * Landing host with bottom navigation between the Notes browser and the Calendar
 * view. The selected tab is remembered across config changes; each tab's own lazy
 * scroll state is restored on return.
 */
@Composable
fun HomeScreen(
    onOpenNote: (pageId: String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.NOTES) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.NOTES,
                    onClick = { tab = HomeTab.NOTES },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text("Notes") },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.CALENDAR,
                    onClick = { tab = HomeTab.CALENDAR },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text("Calendar") },
                )
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                HomeTab.NOTES -> NoteListScreen(
                    onOpenNote = onOpenNote,
                    onOpenSettings = onOpenSettings,
                )
                HomeTab.CALENDAR -> CalendarScreen(
                    onOpenNote = onOpenNote,
                )
            }
        }
    }
}
