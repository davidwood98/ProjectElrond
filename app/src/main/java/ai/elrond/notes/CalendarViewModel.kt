package ai.elrond.notes

import ai.elrond.calendar.DayActivity
import ai.elrond.calendar.NoteActivityMapper
import ai.elrond.data.NoteRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Backs the calendar view: per-day note activity derived from the note timeline. */
@HiltViewModel
class CalendarViewModel(
    private val repository: NoteRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    /** Hilt entry point; the [zone]-injecting primary constructor stays for tests. */
    @Inject
    constructor(repository: NoteRepository) : this(repository, ZoneId.systemDefault())

    /** Latest pages + edit days, kept current regardless of which UI flows are being collected. */
    private var latestPages: List<NotePage> = emptyList()
    private var latestEditDays: List<NoteEditDay> = emptyList()

    init {
        viewModelScope.launch {
            combine(repository.observeTimeline(), repository.observeEditEvents()) { pages, edits ->
                latestPages = pages
                latestEditDays = edits
            }.collect { }
        }
    }

    val activityByDay: StateFlow<Map<LocalDate, DayActivity>> =
        combine(repository.observeTimeline(), repository.observeEditEvents()) { pages, edits ->
            NoteActivityMapper.activityByDay(pages, edits, zone)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val hasAnyNotes: StateFlow<Boolean> = repository.observeTimeline()
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Notes created or edited on [date] (for the day bottom sheet). */
    fun notesForDay(date: LocalDate): List<NotePage> =
        NoteActivityMapper.notesForDay(latestPages, date, latestEditDays, zone)

    fun createNote(onCreated: (pageId: String) -> Unit) {
        viewModelScope.launch {
            val notebook = repository.ensureDefaultNotebook()
            val page = repository.createPage(notebookId = notebook.id)
            onCreated(page.id)
        }
    }
}

