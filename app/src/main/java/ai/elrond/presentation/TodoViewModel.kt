package ai.elrond.presentation

import ai.elrond.todo.TodoPriority
import ai.elrond.todo.TodoItem
import ai.elrond.data.TodoRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives the TODO panel and the toolbar count badge. */
@HiltViewModel
class TodoViewModel @Inject constructor(
    private val repository: TodoRepository,
) : ViewModel() {

    val items: StateFlow<List<TodoItem>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun edit(id: String, content: String, priority: TodoPriority, dueAt: Long?) {
        if (content.isBlank()) return
        viewModelScope.launch { repository.editContent(id, content, priority, dueAt) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.delete(id) }
    }
}
