package ai.elrond.data

import ai.elrond.domain.TodoItem
import ai.elrond.domain.TodoPriority
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for the persistent TODO list.
 *
 * The clock and id generator are injectable for deterministic unit tests.
 */
class TodoRepository(
    private val todoDao: TodoDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    fun observeAll(): Flow<List<TodoItem>> =
        todoDao.observeAll().map { entities -> entities.map(TodoItemEntity::toDomain) }

    /** Normalized contents of all existing items — used to skip already-captured tasks. */
    suspend fun existingContents(): Set<String> =
        todoDao.allContents().map { it.trim().lowercase() }.toSet()

    fun observeActive(): Flow<List<TodoItem>> =
        todoDao.observeActive().map { entities -> entities.map(TodoItemEntity::toDomain) }

    /** Outstanding count for the toolbar badge. */
    fun observeActiveCount(): Flow<Int> = todoDao.observeActiveCount()

    /** Manual entry from the TODO panel. */
    suspend fun addManual(
        content: String,
        priority: TodoPriority = TodoPriority.NONE,
        dueAt: Long? = null,
    ): TodoItem {
        val item = TodoItem(
            id = newId(),
            content = content.trim(),
            priority = priority,
            dueAt = dueAt,
            isAiExtracted = false,
            createdAt = clock(),
        )
        todoDao.insert(item.toEntity())
        return item
    }

    /** Bulk-insert AI-extracted items, all linked to the originating note page. */
    suspend fun addExtracted(
        items: List<ExtractedTask>,
        sourcePageId: String,
        sourcePageTitle: String,
    ): List<TodoItem> {
        if (items.isEmpty()) return emptyList()
        val now = clock()
        val domain = items.map { task ->
            TodoItem(
                id = newId(),
                content = task.content.trim(),
                priority = task.priority,
                dueAt = task.dueAt,
                sourcePageId = sourcePageId,
                sourcePageTitle = sourcePageTitle,
                isAiExtracted = true,
                createdAt = now,
            )
        }
        todoDao.insertAll(domain.map { it.toEntity() })
        return domain
    }

    suspend fun setCompleted(id: String, completed: Boolean) {
        todoDao.setCompleted(id, completed, completedAt = if (completed) clock() else null)
    }

    suspend fun editContent(id: String, content: String, priority: TodoPriority, dueAt: Long?) {
        val existing = todoDao.getById(id) ?: return
        todoDao.update(
            existing.copy(
                title = content.trim(),
                priority = priority.ordinal,
                dueAt = dueAt,
            ),
        )
    }

    suspend fun delete(id: String) {
        todoDao.deleteById(id)
    }

    /** Plain task data the AI extractor produces, before it becomes a persisted item. */
    data class ExtractedTask(
        val content: String,
        val priority: TodoPriority = TodoPriority.NONE,
        val dueAt: Long? = null,
    )
}
