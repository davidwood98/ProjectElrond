package ai.elrond.presentation

import ai.elrond.domain.NotePage
import ai.elrond.data.ThumbnailCache
import ai.elrond.data.NoteRepository
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Landing page: timeline of saved note pages. */
@HiltViewModel
class NoteListViewModel @Inject constructor(
    private val repository: NoteRepository,
    private val thumbnailCache: ThumbnailCache,
) : ViewModel() {

    /** All pages, most recently edited first. */
    val pages: StateFlow<List<NotePage>> = repository.observeTimeline()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * "Recent" notes (FA-15): only those opened within the last 24h, most-recently-opened first.
     * Backs the home Recents tab and the editor note tabs. Re-evaluated on each DB change (and an
     * open updates `lastOpenedAt`, which triggers one), so it stays fresh as notes are visited.
     */
    val recentNotes: StateFlow<List<NotePage>> = repository.observeTimeline()
        .map { pages ->
            val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
            pages.filter { it.lastOpenedAt >= cutoff }.sortedByDescending { it.lastOpenedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Creates a page in the default notebook and reports its id for navigation. */
    fun createNote(onCreated: (pageId: String) -> Unit) {
        viewModelScope.launch {
            val notebook = repository.ensureDefaultNotebook()
            val page = repository.createPage(notebookId = notebook.id)
            onCreated(page.id)
        }
    }

    fun deleteNote(pageId: String) {
        viewModelScope.launch {
            repository.deletePage(pageId)
            thumbnailCache.delete(pageId) // drop the cached WebP so stale files don't accumulate
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
