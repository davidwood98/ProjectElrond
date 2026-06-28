package ai.elrond.ui

import ai.elrond.data.CalendarEvent
import ai.elrond.domain.CalendarGrid
import ai.elrond.domain.DayActivity
import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsUiState
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.domain.NotePage
import ai.elrond.ui.theme.Neutral500
import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class CalendarMode { MONTH, WEEK }

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy")
private val WEEK_TITLE = DateTimeFormatter.ofPattern("d MMM")
private val DAY_HEADER = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")
private val EVENT_STAMP = DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm")

/** Muted-green dot marks days a note was created; dark-grey dot marks days one was edited. */
private val CreatedDotColor = Color(0xFF66BB6A)
private val EditedDotColor = Color(0xFF616161)

/**
 * Timeline calendar over the existing note database (no new data): a tight Month/Week grid with
 * inline period controls (FA-15), each day tile dotted green (created) / grey (edited). Tapping a
 * day shows that day's notes as reduced thumbnails inline beneath the grid (home-card style, each
 * labelled created/edited) — replacing the old bottom-sheet pull-up.
 *
 * [showEvents] is retained for source-compat; the Events list now lives under the Calendar nav, so
 * this screen is Month/Week only.
 */
@Composable
fun CalendarScreen(
    onOpenNote: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") showEvents: Boolean = true,
    viewModel: CalendarViewModel = hiltViewModel(),
    eventsViewModel: EventsViewModel = hiltViewModel(),
    noteListViewModel: NoteListViewModel = hiltViewModel(),
) {
    val activity by viewModel.activityByDay.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    // Explicit shared keys so Month/Week, the anchor period, and the selected day persist across an
    // orientation change (the Timeline lives in the orientation-branched Library; see NotesSection).
    var mode by rememberSaveable(key = "calendar.mode") { mutableStateOf(CalendarMode.MONTH) }
    var anchor by rememberSaveable(key = "calendar.anchor") { mutableStateOf(today.toEpochDay()) }
    val anchorDate = LocalDate.ofEpochDay(anchor)
    var selectedDay by rememberSaveable(key = "calendar.selectedDay") { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedDay)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        // Taller tiles so the grid isn't cramped (two +20% bumps over the original 46/64).
        val monthTileHeight = 68.dp
        val weekTileHeight = 94.dp
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
            // Inline controls: prev/next arrows + period label on the left, Month/Week toggle on the right.
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    anchor = if (mode == CalendarMode.MONTH) anchorDate.minusMonths(1).toEpochDay()
                    else anchorDate.minusWeeks(1).toEpochDay()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                }
                Text(
                    text = periodLabel(mode, anchorDate),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                IconButton(onClick = {
                    anchor = if (mode == CalendarMode.MONTH) anchorDate.plusMonths(1).toEpochDay()
                    else anchorDate.plusWeeks(1).toEpochDay()
                }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
                Spacer(Modifier.weight(1f))
                ModeToggle(mode = mode, onSelect = { mode = it })
            }

            WeekdayHeader()

            when (mode) {
                CalendarMode.MONTH -> {
                    CalendarGrid.monthGrid(YearMonth.from(anchorDate)).chunked(7).forEach { week ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            week.forEach { date ->
                                DayTile(
                                    date = date,
                                    inPeriod = YearMonth.from(date) == YearMonth.from(anchorDate),
                                    isToday = date == today,
                                    isSelected = date == selected,
                                    activity = activity[date],
                                    onClick = { selectedDay = it.toEpochDay() },
                                    tileHeight = monthTileHeight,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                CalendarMode.WEEK -> {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        CalendarGrid.weekDays(anchorDate).forEach { date ->
                            DayTile(
                                date = date,
                                inPeriod = true,
                                isToday = date == today,
                                isSelected = date == selected,
                                activity = activity[date],
                                onClick = { selectedDay = it.toEpochDay() },
                                tileHeight = weekTileHeight,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            Legend(modifier = Modifier.padding(top = 10.dp))

            // Selected-day notes (reduced home-style thumbnails, created/edited labels).
            val dayNotes = remember(selectedDay, activity) { viewModel.notesForDay(selected) }
            Text(
                selected.format(DAY_HEADER),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
            // Notes fill the remaining space: a single horizontal row (landscape month — runs off the
            // right edge), or a vertical grid (week in either orientation, and month in portrait).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (dayNotes.isEmpty()) {
                    Text(
                        "No notes on this day.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Neutral500,
                    )
                } else if (landscape && mode == CalendarMode.MONTH) {
                    // One row that fills the remaining height down to the screen bottom (tiles run
                    // behind the New-note FAB) and scrolls horizontally off the right edge.
                    Row(
                        modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        dayNotes.forEach { note ->
                            DayNoteThumb(
                                note = note,
                                date = selected,
                                viewModel = noteListViewModel,
                                onClick = { onOpenNote(note.id) },
                                modifier = Modifier.fillMaxHeight().width(190.dp),
                                notebookLabel = viewModel.notebookPageLabel(note),
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        gridItems(dayNotes, key = { it.id }) { note ->
                            DayNoteThumb(
                                note = note,
                                date = selected,
                                viewModel = noteListViewModel,
                                onClick = { onOpenNote(note.id) },
                                modifier = Modifier.fillMaxWidth().height(150.dp),
                                notebookLabel = viewModel.notebookPageLabel(note),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun periodLabel(mode: CalendarMode, anchor: LocalDate): String = when (mode) {
    CalendarMode.MONTH -> YearMonth.from(anchor).format(MONTH_TITLE)
    CalendarMode.WEEK -> {
        val start = anchor.with(java.time.DayOfWeek.MONDAY)
        val end = start.plusDays(6)
        "${start.format(WEEK_TITLE)} – ${end.format(WEEK_TITLE)} ${end.year}"
    }
}

/** Month / Week segmented toggle — a pill track with the active mode in a white pill. */
@Composable
private fun ModeToggle(mode: CalendarMode, onSelect: (CalendarMode) -> Unit) {
    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            CalendarMode.entries.forEach { m ->
                val active = m == mode
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (active) 1.dp else 0.dp,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).clickable { onSelect(m) },
                ) {
                    Text(
                        m.name.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (active) MaterialTheme.colorScheme.primary else Neutral500,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    Row(modifier = Modifier.fillMaxWidth()) {
        CalendarGrid.weekDays(LocalDate.now()).forEach { d ->
            Text(
                text = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = Neutral500,
                modifier = Modifier.weight(1f).padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun DayTile(
    date: LocalDate,
    inPeriod: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    activity: DayActivity?,
    onClick: (LocalDate) -> Unit,
    tileHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val hasActivity = activity != null && !activity.isEmpty
    Box(
        modifier = modifier
            .height(tileHeight)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isToday) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .then(
                if (isSelected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
            )
            .clickable { onClick(date) },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (inPeriod) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            Spacer(Modifier.weight(1f))
            if (activity != null && inPeriod && hasActivity) {
                // Dot + count per type: green = created, grey = edited (the "Nx" the user wants back).
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (activity.hasCreated) ActivityDot(CreatedDotColor, activity.created)
                    if (activity.hasEdited) ActivityDot(EditedDotColor, activity.edited)
                }
            }
        }
    }
}

/** A status dot with its note count beside it (e.g. green ● 3 = three notes created that day). */
@Composable
private fun ActivityDot(color: Color, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
        if (count > 0) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                color = Neutral500,
            )
        }
    }
}

/** A reduced note thumbnail for the selected day (home-card style) with a created/edited label. */
@Composable
private fun DayNoteThumb(
    note: NotePage,
    date: LocalDate,
    viewModel: NoteListViewModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    notebookLabel: String? = null,
) {
    val zone = java.time.ZoneId.systemDefault()
    val createdThisDay = java.time.Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate() == date
    val verb = if (createdThisDay) "Created" else "Edited"
    val verbColor = if (createdThisDay) CreatedDotColor else EditedDotColor
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
    ) {
        // The tile is given a bounded height by the caller (grid: fixed; landscape-month row:
        // fillMaxHeight), so the thumbnail takes the remaining height above the label.
        Column(modifier = Modifier.fillMaxSize()) {
            NoteThumbnail(
                page = note,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().weight(1f).background(MaterialTheme.colorScheme.surfaceContainerLowest),
            )
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    note.displayTitle(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // For a multi-page notebook, show which notebook + page this is (FA-20).
                if (notebookLabel != null) {
                    Text(
                        notebookLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Neutral500,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(verbColor))
                    Spacer(Modifier.width(5.dp))
                    Text(verb, style = MaterialTheme.typography.labelSmall, color = Neutral500)
                }
            }
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(CreatedDotColor))
            Text("Created", style = MaterialTheme.typography.labelMedium, color = Neutral500)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(EditedDotColor))
            Text("Edited", style = MaterialTheme.typography.labelMedium, color = Neutral500)
        }
    }
}

/**
 * The Events tab: shows upcoming events from the selected calendar provider, or — when Outlook is
 * selected but not connected — a standard "Sign in with Microsoft" link (per FA-11). Stateless about
 * Graph/MSAL; everything comes from [EventsViewModel.uiState].
 */
@Composable
internal fun EventsTab(viewModel: EventsViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    when (val s = state) {
        is EventsUiState.Loading -> CenteredEvents {
            CircularProgressIndicator()
        }

        is EventsUiState.NotConfigured -> OutlookSignInPrompt(
            caption = "Outlook isn't configured in this build. Add an Azure client id to " +
                "local.properties to connect your Microsoft account (see CLAUDE.md).",
            onSignIn = { activity?.let(viewModel::signIn) },
        )

        is EventsUiState.NeedsSignIn -> OutlookSignInPrompt(
            caption = "Connect your Microsoft account to see your Outlook calendar here.",
            onSignIn = { activity?.let(viewModel::signIn) },
        )

        is EventsUiState.Error -> CenteredEvents {
            Text(
                "Couldn't load events:\n${s.message}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
            androidx.compose.material3.TextButton(onClick = viewModel::retry) { Text("Retry") }
        }

        is EventsUiState.Events -> EventsList(state = s, onSignOut = viewModel::signOut)
    }
}

@Composable
private fun CenteredEvents(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun OutlookSignInPrompt(caption: String, onSignIn: () -> Unit) {
    CenteredEvents {
        Text(
            "Outlook calendar",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            caption,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MicrosoftSignInButton(onClick = onSignIn)
    }
}

@Composable
private fun EventsList(state: EventsUiState.Events, onSignOut: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.providerType == ai.elrond.data.CalendarProviderType.OUTLOOK) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    state.signedInAs?.let { "Signed in as $it" } ?: "Outlook calendar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.TextButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
        if (state.events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No upcoming events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.events, key = { it.id ?: (it.title + it.startTime) }) { event ->
                    EventCard(event)
                }
            }
        }
    }
}

@Composable
private fun EventCard(event: CalendarEvent) {
    val zone = java.time.ZoneId.systemDefault()
    val start = java.time.Instant.ofEpochMilli(event.startTime).atZone(zone)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(event.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleSmall)
            Text(
                EVENT_STAMP.format(start),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            event.location?.takeIf { it.isNotBlank() }?.let {
                Text("📍 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A standard "Sign in with Microsoft" button: the four-colour Microsoft squares + label, matching
 * Microsoft's branding guidance, drawn inline so it needs no bundled asset.
 */
@Composable
private fun MicrosoftSignInButton(onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(onClick = onClick) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val gap = size.width * 0.08f
            val cell = (size.width - gap) / 2f
            drawRect(Color(0xFFF25022), size = androidx.compose.ui.geometry.Size(cell, cell), topLeft = Offset(0f, 0f))
            drawRect(Color(0xFF7FBA00), size = androidx.compose.ui.geometry.Size(cell, cell), topLeft = Offset(cell + gap, 0f))
            drawRect(Color(0xFF00A4EF), size = androidx.compose.ui.geometry.Size(cell, cell), topLeft = Offset(0f, cell + gap))
            drawRect(Color(0xFFFFB900), size = androidx.compose.ui.geometry.Size(cell, cell), topLeft = Offset(cell + gap, cell + gap))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text("Sign in with Microsoft")
    }
}
