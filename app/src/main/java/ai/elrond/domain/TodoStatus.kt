package ai.elrond.domain

/**
 * Workflow status of a to-do item (FA-14 Kanban board). [DONE] is equivalent to the legacy
 * `isCompleted` flag (the two are kept in sync at the data layer); [TODO] and [IN_PROGRESS] are the
 * two open states. Persisted as the enum ordinal in a Room INTEGER column.
 */
enum class TodoStatus {
    TODO,
    IN_PROGRESS,
    DONE;

    val isDone: Boolean get() = this == DONE

    companion object {
        val DEFAULT = TODO

        fun fromName(name: String?): TodoStatus = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Round-trips the Room INTEGER column (the enum ordinal). */
        fun fromInt(value: Int?): TodoStatus = entries.getOrNull(value ?: 0) ?: DEFAULT
    }
}
