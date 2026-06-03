package ai.elrond.notes

import ai.elrond.data.NoteRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Landing page: timeline of saved note pages. */
class NoteListViewModel(
    private val repository: NoteRepository,
) : ViewModel() {

    /** All pages, most recently edited first. */
    val pages: StateFlow<List<NotePage>> = repository.observeTimeline()
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
        viewModelScope.launch { repository.deletePage(pageId) }
    }

    /** Normalized stroke polylines for the card thumbnail. */
    suspend fun preview(pageId: String): List<List<Pair<Float, Float>>> =
        runCatching { repository.loadStrokePreview(pageId) }.getOrDefault(emptyList())
}

fun noteListViewModelFactory(repository: NoteRepository): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { NoteListViewModel(repository) }
    }
