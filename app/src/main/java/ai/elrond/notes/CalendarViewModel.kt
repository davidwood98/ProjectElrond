package ai.elrond.notes

import ai.elrond.calendar.DayActivity
import ai.elrond.calendar.NoteActivityMapper
import ai.elrond.data.NoteRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the calendar view: per-day note activity derived from the note timeline. */
class CalendarViewModel(
    private val repository: NoteRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    /** Latest pages, kept current regardless of which UI flows are being collected. */
    private var latestPages: List<NotePage> = emptyList()

    init {
        viewModelScope.launch { repository.observeTimeline().collect { latestPages = it } }
    }

    val activityByDay: StateFlow<Map<LocalDate, DayActivity>> = repository.observeTimeline()
        .map { NoteActivityMapper.activityByDay(it, zone) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val hasAnyNotes: StateFlow<Boolean> = repository.observeTimeline()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Notes created or last-edited on [date] (for the day bottom sheet). */
    fun notesForDay(date: LocalDate): List<NotePage> =
        NoteActivityMapper.notesForDay(latestPages, date, zone)

    fun createNote(onCreated: (pageId: String) -> Unit) {
        viewModelScope.launch {
            val notebook = repository.ensureDefaultNotebook()
            val page = repository.createPage(notebookId = notebook.id)
            onCreated(page.id)
        }
    }
}

fun calendarViewModelFactory(repository: NoteRepository): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { CalendarViewModel(repository) }
    }
