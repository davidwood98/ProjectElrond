package ai.elrond.presentation

import ai.elrond.domain.NoteEditDay
import ai.elrond.domain.NotePage
import ai.elrond.domain.DayActivity
import ai.elrond.domain.NoteActivityMapper
import ai.elrond.data.NoteRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
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

    /**
     * The day's activity grouped by **notebook** (FA-20): one tile per notebook rather than per page.
     * A notebook whose cover (page 1) was created on [date] is a *created* entry (shown without a
     * page count, tapping opens it); otherwise it's an *edited* entry carrying the pages touched that
     * day (a count pill, tapping lists them). Sorted most-recently-active first.
     */
    fun notebooksForDay(date: LocalDate): List<DayNotebook> {
        val dayPages = NoteActivityMapper.notesForDay(latestPages, date, latestEditDays, zone)
        return dayPages.groupBy { it.notebookId }.map { (notebookId, pages) ->
            val all = latestPages.filter { it.notebookId == notebookId }
            val cover = all.minByOrNull { it.pageNumber } ?: pages.first()
            val createdThisDay =
                Instant.ofEpochMilli(cover.createdAt).atZone(zone).toLocalDate() == date
            DayNotebook(
                notebookId = notebookId,
                coverPage = cover,
                createdThisDay = createdThisDay,
                pages = pages.sortedBy { it.pageNumber },
            )
        }.sortedByDescending { group -> group.pages.maxOf { it.modifiedAt } }
    }

    fun createNote(onCreated: (pageId: String) -> Unit) {
        viewModelScope.launch {
            val page = repository.createNote()
            onCreated(page.id)
        }
    }
}

/**
 * A notebook's activity on a calendar day (FA-20): the cover title + the pages touched that day.
 * [createdThisDay] distinguishes a freshly-created notebook (no page count) from an edited one
 * (a count pill + a per-page menu). [coverPageId] is the page a "created" tile opens.
 */
data class DayNotebook(
    val notebookId: String,
    /** The notebook's cover (page 1) — its title + the thumbnail; the page a "created" tile opens. */
    val coverPage: NotePage,
    val createdThisDay: Boolean,
    /** The pages of this notebook touched on the day (an "edited" tile lists these). */
    val pages: List<NotePage>,
)

