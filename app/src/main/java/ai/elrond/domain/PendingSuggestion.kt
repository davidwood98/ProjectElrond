package ai.elrond.domain

/** What an AI background-extraction suggestion will become if the user accepts it. */
enum class SuggestionType { TODO, EVENT, TAG }

/**
 * A background-detected item awaiting the user's decision. Times are already resolved to epoch
 * millis by the extraction runner (so the commit path never re-parses). [x]/[y] are canvas-pixel
 * anchors for the on-canvas popup (TODO/EVENT); [id] is assigned by the repository when empty.
 *
 * [notebookId] scopes TAG suggestions (FA-24d Level 2): unlike TODO/EVENT — which anchor to a
 * page's [pageId] and x/y — a suggested tag applies to the whole notebook and has no page anchor,
 * so TAG rows carry [notebookId] and leave [pageId] blank / x,y = 0.
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
    val notebookId: String? = null,
)
