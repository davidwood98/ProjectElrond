package ai.elrond.data

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Builds the FA-24c content-search SQL against the FA-24b `recognized_lines` cache. Plain `LIKE`
 * (case-insensitive for ASCII) — no FTS module, so it works on every device's SQLite (FTS5 is not
 * dependable on Android; see the FA-24c device round). [tokens] are letters/digits only (see
 * [ai.elrond.domain.SearchQuery.tokenize]), so they can't contain `LIKE` wildcards — no escaping
 * needed. A line matches if it contains ANY token (OR); relevance is scored in Kotlin from the
 * returned text, so the closest match still ranks first.
 */
internal object SearchQueries {

    private fun likeClause(tokens: List<String>): String =
        tokens.joinToString(" OR ") { "l.text LIKE '%' || ? || '%'" }

    /** Content line hits within a scoped set of notebooks — drives the ranked tile grid. */
    fun contentInNotebooks(tokens: List<String>, notebookIds: List<String>): SupportSQLiteQuery {
        val placeholders = notebookIds.joinToString(",") { "?" }
        return SimpleSQLiteQuery(
            "SELECT np.notebookId AS notebookId, l.pageId AS pageId, l.id AS lineId, l.text AS text, " +
                "l.minX AS minX, l.minY AS minY, l.maxX AS maxX, l.maxY AS maxY " +
                "FROM recognized_lines l JOIN note_pages np ON np.id = l.pageId " +
                "WHERE np.notebookId IN ($placeholders) AND (${likeClause(tokens)})",
            (notebookIds + tokens).toTypedArray(),
        )
    }

    /** Content line hits on a single page — drives the on-canvas highlight boxes. */
    fun contentOnPage(tokens: List<String>, pageId: String): SupportSQLiteQuery = SimpleSQLiteQuery(
        "SELECT np.notebookId AS notebookId, l.pageId AS pageId, l.id AS lineId, l.text AS text, " +
            "l.minX AS minX, l.minY AS minY, l.maxX AS maxX, l.maxY AS maxY " +
            "FROM recognized_lines l JOIN note_pages np ON np.id = l.pageId " +
            "WHERE l.pageId = ? AND (${likeClause(tokens)})",
        (listOf(pageId) + tokens).toTypedArray(),
    )
}
