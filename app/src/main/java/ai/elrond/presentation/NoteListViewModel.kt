package ai.elrond.presentation

import ai.elrond.domain.NotePage
import ai.elrond.domain.NotebookSummary
import ai.elrond.data.SessionNotesTracker
import ai.elrond.data.ThumbnailCache
import ai.elrond.data.NoteRepository
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Landing page: timeline of saved note pages. */
@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val thumbnailCache: ThumbnailCache,
    // Defaulted so existing direct constructions in tests still compile; Hilt injects the singleton.
    private val sessionNotesTracker: SessionNotesTracker = SessionNotesTracker(),
) : ViewModel() {

    /** All pages, most recently edited first. */
    val pages: StateFlow<List<NotePage>> = repository.observeTimeline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * "Recent" notes (FA-15): only those opened within the last 24h, most-recently-opened first.
     * Backs the home Recents tab. (The editor note tabs use [sessionNotes] — a session-scoped,
     * in-memory list — since FA-16.) Re-evaluated on each DB change (and an open updates
     * `lastOpenedAt`, which triggers one), so it stays fresh as notes are visited.
     */
    val recentNotes: StateFlow<List<NotePage>> = repository.observeTimeline()
        .map { pages ->
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            pages.filter { it.lastOpenedAt >= cutoff }.sortedByDescending { it.lastOpenedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Notes opened in the current foreground session (FA-16) — backs the editor's note tabs (which
     * show "this session" instead of the old 24h "Recent" window). Ordered most-recently-opened
     * first; the [SessionNotesTracker] is cleared when the app backgrounds, so this resets per session.
     */
    val sessionNotes: StateFlow<List<NotePage>> = combine(
        sessionNotesTracker.openedPageIds,
        repository.observeTimeline(),
    ) { ids, pages ->
        val byId = pages.associateBy { it.id }
        ids.mapNotNull { byId[it] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** One summary per notebook (FA-20), most-recently-opened first — backs the browser grid. */
    val notebooks: StateFlow<List<NotebookSummary>> = repository.observeNotebookSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Notebooks touched in the current foreground session (FA-20) — the editor's note tabs. Derived
     * from the session's opened page ids mapped to their notebooks and de-duplicated (opening several
     * pages of one notebook yields a single tab), preserving open order.
     */
    val sessionNotebooks: StateFlow<List<NotebookSummary>> = combine(
        sessionNotesTracker.openedPageIds,
        repository.observeNotebookSummaries(),
        repository.observeTimeline(),
    ) { openedIds, summaries, pages ->
        val pageToNotebook = pages.associate { it.id to it.notebookId }
        val summaryById = summaries.associateBy { it.notebookId }
        openedIds.mapNotNull { pid -> pageToNotebook[pid]?.let(summaryById::get) }.distinctBy { it.notebookId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Notebooks opened within the last 24h (FA-20), most-recently-opened first — the Recents tab. */
    val recentNotebooks: StateFlow<List<NotebookSummary>> = notebooks
        .map { list ->
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            list.filter { it.lastOpenedAt >= cutoff }.sortedByDescending { it.lastOpenedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Renames a note; a blank title reverts it to the auto-generated timestamp title. */
    fun renameNote(pageId: String, title: String) {
        viewModelScope.launch { repository.renamePage(pageId, title.trim().ifEmpty { null }) }
    }

    /** Creates a new notebook with its first page and reports the page id for navigation (FA-20). */
    fun createNote(onCreated: (pageId: String) -> Unit) {
        viewModelScope.launch {
            val page = repository.createNote()
            onCreated(page.id)
        }
    }

    fun deleteNote(pageId: String) {
        viewModelScope.launch {
            repository.deletePage(pageId)
            thumbnailCache.delete(pageId) // drop the cached WebP so stale files don't accumulate
        }
    }

    /** Deletes a whole notebook (all its pages) and drops the cached cover thumbnail (FA-20). */
    fun deleteNotebook(notebookId: String, coverPageId: String) {
        viewModelScope.launch {
            repository.deleteNotebook(notebookId)
            thumbnailCache.delete(coverPageId)
        }
    }

    /** Cached WebP thumbnail for the card (decoded off the main thread), or null if none exists yet. */
    suspend fun thumbnail(pageId: String): Bitmap? = thumbnailCache.read(pageId)

    /** Normalized stroke polylines — the fallback thumbnail when no cached bitmap exists yet. */
    suspend fun preview(pageId: String): List<List<Pair<Float, Float>>> =
        runCatching { repository.loadStrokePreview(pageId) }.getOrDefault(emptyList())

    private companion object {
        const val RECENT_WINDOW_MS = 24L * 60 * 60 * 1000 // 24 hours
    }
}
