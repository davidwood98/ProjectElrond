package ai.elrond.domain

/** What an AI background-extraction suggestion will become if the user accepts it. */
enum class SuggestionType { TODO, EVENT }

/**
 * A background-detected item awaiting the user's on-canvas Yes/No decision. Times are
 * already resolved to epoch millis by the extraction runner (so the popup/commit path
 * never re-parses). [x]/[y] are canvas-pixel anchors for the popup (clamped to the
 * visible canvas by the UI). [id] is assigned by the repository when empty.
 */
data class PendingSuggestion(
    val pageId: String,
    val type: SuggestionType,
    val content: String,
    val x: Float,
    val y: Float,
    val id: String = "",
    val dueAtMillis: Long? = null,
    val priority: Int = 0,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val location: String? = null,
)
