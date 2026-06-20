package ai.elrond.domain

/**
 * Tasks the AI extracted from the current page, awaiting the user's confirmation
 * before they're saved to the TODO list (calendar-style "confirm before write").
 */
data class PendingTaskExtraction(
    val tasks: List<String>,
    val sourcePageTitle: String,
) {
    val count: Int get() = tasks.size
}
