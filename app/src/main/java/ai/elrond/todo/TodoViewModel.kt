package ai.elrond.todo

import ai.elrond.data.TodoRepository
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Drives the TODO panel and the toolbar count badge. */
class TodoViewModel(
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

fun todoViewModelFactory(repository: TodoRepository): ViewModelProvider.Factory =
    viewModelFactory {
        initializer { TodoViewModel(repository) }
    }
