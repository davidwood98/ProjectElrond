package ai.elrond.domain

/** Priority levels for a TODO item. Ordinal maps to the stored integer. */
enum class TodoPriority { NONE, LOW, MEDIUM, HIGH }

/**
 * A task in the persistent TODO list. May be entered manually or extracted by
 * the AI from note content; [isAiExtracted] drives the visual distinction and
 * [sourcePageId] links it back to the note it came from.
 *
 * [status] is the FA-14 workflow state (To-do / In progress / Done) backing the Kanban board;
 * [isCompleted] is derived from it ([TodoStatus.DONE]) so existing binary call-sites keep working.
 */
data class TodoItem(
    val id: String,
    val content: String,
    val status: TodoStatus = TodoStatus.TODO,
    val dueAt: Long? = null,
    val priority: TodoPriority = TodoPriority.NONE,
    val sourcePageId: String? = null,
    val sourcePageTitle: String? = null,
    val isAiExtracted: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
) {
    /** Binary completion, derived from [status] — the legacy flag the panel checkbox reads. */
    val isCompleted: Boolean get() = status == TodoStatus.DONE

    /** True when tapping the item should navigate to its still-existing source note. */
    val hasSourceLink: Boolean get() = sourcePageId != null
}
