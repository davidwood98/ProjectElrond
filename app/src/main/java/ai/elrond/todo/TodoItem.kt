package ai.elrond.todo

/** Priority levels for a TODO item. Ordinal maps to the stored integer. */
enum class TodoPriority { NONE, LOW, MEDIUM, HIGH }

/**
 * A task in the persistent TODO list. May be entered manually or extracted by
 * the AI from note content; [isAiExtracted] drives the visual distinction and
 * [sourcePageId] links it back to the note it came from.
 */
data class TodoItem(
    val id: String,
    val content: String,
    val isCompleted: Boolean = false,
    val dueAt: Long? = null,
    val priority: TodoPriority = TodoPriority.NONE,
    val sourcePageId: String? = null,
    val sourcePageTitle: String? = null,
    val isAiExtracted: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
) {
    /** True when tapping the item should navigate to its still-existing source note. */
    val hasSourceLink: Boolean get() = sourcePageId != null
}
