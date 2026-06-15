package ai.elrond.ui

import ai.elrond.calendar.CalendarGrid
import ai.elrond.calendar.DayActivity
import ai.elrond.notes.CalendarViewModel
import ai.elrond.notes.NotePage
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private enum class CalendarMode { MONTH, WEEK, EVENTS }

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM yyyy")
private val DAY_TIME = DateTimeFormatter.ofPattern("HH:mm")

/** Muted-green dot marks days a note was created; dark-grey dot marks days one was edited. */
private val CreatedDotColor = Color(0xFF66BB6A)
private val EditedDotColor = Color(0xFF616161)

/**
 * Calendar view over the existing note database (no new data): each day tile shows
 * a muted-green dot when notes were created and a dark-grey dot when notes were
 * edited. Month / Week / Events modes; tapping an active day opens that day's notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onOpenNote: (pageId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CalendarViewModel = hiltViewModel(),
) {
    val activity by viewModel.activityByDay.collectAsStateWithLifecycle()
    val today = remember { LocalDate.now() }

    var mode by rememberSaveable { mutableStateOf(CalendarMode.MONTH) }
    var anchor by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val anchorDate = LocalDate.ofEpochDay(anchor)
    var selectedDay by remember { mutableStateOf<LocalDate?>(null) }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        // Mode switch
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CalendarMode.entries.forEach { m ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (mode != CalendarMode.EVENTS) {
            // Period navigation
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(onClick = {
                    anchor = if (mode == CalendarMode.MONTH) anchorDate.minusMonths(1).toEpochDay()
                    else anchorDate.minusWeeks(1).toEpochDay()
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous")
                }
                Text(
                    text = if (mode == CalendarMode.MONTH) {
                        YearMonth.from(anchorDate).format(MONTH_TITLE)
                    } else {
                        "Week of ${anchorDate.with(java.time.DayOfWeek.MONDAY)}"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = {
                    anchor = if (mode == CalendarMode.MONTH) anchorDate.plusMonths(1).toEpochDay()
                    else anchorDate.plusWeeks(1).toEpochDay()
                }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next")
                }
            }
            WeekdayHeader()
        }

        when (mode) {
            CalendarMode.MONTH -> {
                val days = CalendarGrid.monthGrid(YearMonth.from(anchorDate))
                days.chunked(7).forEach { week ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            DayTile(
                                date = date,
                                inPeriod = YearMonth.from(date) == YearMonth.from(anchorDate),
                                isToday = date == today,
                                activity = activity[date],
                                onClick = { selectedDay = it },
                                onCreateToday = { viewModel.createNote(onOpenNote) },
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
                            activity = activity[date],
                            onClick = { selectedDay = it },
                            onCreateToday = { viewModel.createNote(onOpenNote) },
                            showWeekday = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            CalendarMode.EVENTS -> EventsPlaceholder()
        }

        if (mode != CalendarMode.EVENTS) {
            Legend(modifier = Modifier.padding(top = 12.dp))
        }
    }

    selectedDay?.let { date ->
        val notes = remember(date, activity) { viewModel.notesForDay(date) }
        ModalBottomSheet(
            onDismissRequest = { selectedDay = null },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            ) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                if (notes.isEmpty()) {
                    Text("No notes this day.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(notes, key = { it.id }) { note ->
                            DayNoteCard(note = note, date = date, onClick = {
                                selectedDay = null
                                onOpenNote(note.id)
                            })
                        }
                    }
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
                text = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DayTile(
    date: LocalDate,
    inPeriod: Boolean,
    isToday: Boolean,
    activity: DayActivity?,
    onClick: (LocalDate) -> Unit,
    onCreateToday: () -> Unit,
    modifier: Modifier = Modifier,
    showWeekday: Boolean = false,
) {
    val hasActivity = activity != null && !activity.isEmpty
    val bg = when {
        !inPeriod -> Color.Transparent
        hasActivity -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }
    Box(
        modifier = modifier
            .aspectRatio(if (showWeekday) 0.5f else 1f)
            .padding(2.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .then(
                if (isToday) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                else Modifier,
            )
            .then(if (hasActivity) Modifier.clickable { onClick(date) } else Modifier),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            Text(
                text = if (showWeekday) {
                    "${date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${date.dayOfMonth}"
                } else {
                    date.dayOfMonth.toString()
                },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (inPeriod) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
            if (activity != null && inPeriod) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (activity.hasCreated) {
                        ActivityDot(color = CreatedDotColor, count = activity.created)
                    }
                    if (activity.hasEdited) {
                        ActivityDot(color = EditedDotColor, count = activity.edited)
                    }
                }
            }
            // On today's empty tile, show the "slow day" nudge above a quick create shortcut.
            if (isToday && (activity == null || activity.isEmpty)) {
                Text(
                    "Slow day?",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                IconButton(
                    onClick = onCreateToday,
                    modifier = Modifier.align(Alignment.CenterHorizontally).size(32.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New note today")
                }
            }
        }
    }
}

@Composable
private fun DayNoteCard(note: NotePage, date: LocalDate, onClick: () -> Unit) {
    val zone = java.time.ZoneId.systemDefault()
    // On its creation day the note reads as "created"; on any later day it reads as "edited".
    val createdThisDay = java.time.Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalDate() == date
    val verb = if (createdThisDay) "created" else "edited"
    val stampMillis = if (createdThisDay) note.createdAt else note.modifiedAt
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(note.displayTitle(), style = MaterialTheme.typography.titleSmall)
            Text(
                "$verb ${DAY_TIME.format(java.time.Instant.ofEpochMilli(stampMillis).atZone(zone))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A small coloured status dot, optionally trailed by a count when more than one. */
@Composable
private fun ActivityDot(color: Color, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color),
        )
        if (count > 1) {
            Text(
                text = count.toString(),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

@Composable
private fun Legend(modifier: Modifier = Modifier) {
    Surface(modifier = modifier, tonalElevation = 1.dp, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ActivityDot(color = CreatedDotColor, count = 1)
                Text("created", style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                ActivityDot(color = EditedDotColor, count = 1)
                Text("edited", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun EventsPlaceholder() {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            "Calendar events will appear here.\n\nThis is where an integrated calendar " +
                "(device, Google, or Outlook via the CalendarProvider layer) will populate " +
                "full event details. Wiring is stubbed for now — see CLAUDE.md.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
