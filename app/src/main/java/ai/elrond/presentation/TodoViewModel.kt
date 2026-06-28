package ai.elrond.presentation

import ai.elrond.domain.TodoPriority
import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoStatus
import ai.elrond.domain.NotePage
import ai.elrond.domain.SourceNoteLabel
import ai.elrond.data.NoteRepository
import ai.elrond.data.TodoRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives the TODO panel and the toolbar count badge. */
@HiltViewModel
class TodoViewModel(
    private val repository: TodoRepository,
    // The notebook page list, for resolving AI-item source links. A Flow (not the repository) so this
    // primary constructor's JVM signature stays distinct from the @Inject one — tests pass only the
    // TodoRepository and get the empty default.
    pages: Flow<List<NotePage>> = flowOf(emptyList()),
) : ViewModel() {

    /** Hilt entry point; the page list comes from the NoteRepository timeline. */
    @Inject
    constructor(repository: TodoRepository, noteRepository: NoteRepository) :
        this(repository, noteRepository.observeTimeline())

    val items: StateFlow<List<TodoItem>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Live "Notebook → Page N" labels for AI-extracted items, keyed by sourcePageId (FA-20). Resolved
     * from the current page list so a moved page reflects its new number; a deleted source is absent
     * (the UI falls back to the stored title snapshot). Empty when no page list is wired (unit tests).
     */
    val sourceLabels: StateFlow<Map<String, String>> = pages
        .map { current ->
            current.mapNotNull { p -> SourceNoteLabel.resolve(p.id, current)?.let { p.id to it } }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Outstanding count for the toolbar badge. */
    val activeCount: StateFlow<Int> = repository.observeActiveCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun add(content: String, priority: TodoPriority = TodoPriority.NONE) {
        if (content.isBlank()) return
        viewModelScope.launch { repository.addManual(content, priority) }
    }

    fun setCompleted(id: String, completed: Boolean) {
        viewModelScope.launch { repository.setCompleted(id, completed) }
    }

    /** FA-14: move an item to a Kanban column (To-do / In progress / Done). */
    fun setStatus(id: String, status: TodoStatus) {
        viewModelScope.launch { repository.setStatus(id, status) }
    }

    fun edit(id: String, content: String, priority: TodoPriority, dueAt: Long?) {
        if (content.isBlank()) return
        viewModelScope.launch { repository.editContent(id, content, priority, dueAt) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
