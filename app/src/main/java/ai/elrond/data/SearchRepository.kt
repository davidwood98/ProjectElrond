package ai.elrond.data

import ai.elrond.domain.SearchHighlight
import ai.elrond.domain.SearchMatch
import ai.elrond.domain.SearchQuery

/**
 * FA-24c notebook search: merges three independent sources — notebook **title**, **tags**, and
 * handwritten **content** (the FA-24b `recognized_lines` cache) — into one relevance-ranked list of
 * notebook ids that drives the filtered tile grid, and resolves a page's on-canvas highlight boxes.
 * Content search is plain `LIKE` (no FTS module — FTS5 isn't dependable on Android; the cache is
 * already populated so no index/backfill is needed). Search never matches subjects (product decision).
 * Scope — the notebook-id set — is computed by the caller (from the observed library lists) and passed
 * in, so this stays a pure matcher.
 */
class SearchRepository(
    private val notebookDao: NotebookDao,
    private val notebookTagDao: NotebookTagDao,
    private val recognizedLineDao: RecognizedLineDao,
) {

    /**
     * The relevance-ranked notebook ids matching [rawQuery] within [scopeIds] (title/tag/content),
     * for the filtered tile grid. [naturalOrder] (e.g. the tab's own order) breaks ties. Empty query
     * or empty scope → empty result.
     */
    suspend fun rankedNotebookIds(
        rawQuery: String,
        scopeIds: Set<String>,
        naturalOrder: List<String> = emptyList(),
    ): List<String> {
        val tokens = SearchQuery.tokenize(rawQuery)
        if (tokens.isEmpty() || scopeIds.isEmpty()) return emptyList()
        val lowerTokens = tokens.map { it.lowercase() }
        val phrase = rawQuery.trim().lowercase()

        // 1. Title: full-phrase contains vs any-token contains, scoped.
        val titleFull = HashSet<String>()
        val titlePartial = HashSet<String>()
        notebookDao.idsAndNames().forEach { (id, name) ->
            if (id !in scopeIds) return@forEach
            val lower = name.lowercase()
            when {
                phrase.isNotEmpty() && lower.contains(phrase) -> titleFull += id
                lowerTokens.any { lower.contains(it) } -> titlePartial += id
            }
        }

        // 2. Tags: a notebook whose tag name contains any query token.
        val tagMatches = notebookTagDao.getAllWithTag()
            .filter { it.notebookId in scopeIds && lowerTokens.any { t -> it.name.lowercase().contains(t) } }
            .mapTo(HashSet()) { it.notebookId }

        // 3. Content: best per-notebook relevance from the matching lines (scored in Kotlin — count of
        // distinct WHOLE-WORD tokens present, +1 if a line contains the whole phrase, so the closest
        // match ranks top). LIKE is only a cheap prefilter; whole-word matching is enforced here.
        val contentRelevance = HashMap<String, Double>()
        recognizedLineDao.searchContent(SearchQueries.contentInNotebooks(tokens, scopeIds.toList()))
            .forEach { row ->
                val words = wordMatchCount(row.text, lowerTokens)
                if (words == 0) return@forEach // LIKE hit was only a substring (e.g. "is" in "consistent")
                val score = words + (if (phrase.isNotEmpty() && row.text.lowercase().contains(phrase)) 1 else 0)
                contentRelevance.merge(row.notebookId, score.toDouble()) { a, b -> maxOf(a, b) }
            }

        return SearchMatch.rankedNotebookIds(
            scopeIds = scopeIds,
            titleFull = titleFull,
            titlePartial = titlePartial,
            tagMatches = tagMatches,
            contentRelevance = contentRelevance,
            naturalOrder = naturalOrder,
        )
    }

    /**
     * The page ids in [notebookId] with any content match for [query] — FA-24c search-result mode
     * uses this to jump to the first matching page when the opened page has none.
     */
    suspend fun matchingPageIds(notebookId: String, query: String): Set<String> {
        val tokens = SearchQuery.tokenize(query)
        if (tokens.isEmpty()) return emptySet()
        val lowerTokens = tokens.map { it.lowercase() }
        return recognizedLineDao.searchContent(SearchQueries.contentInNotebooks(tokens, listOf(notebookId)))
            .filter { wordMatchCount(it.text, lowerTokens) > 0 }
            .mapTo(HashSet()) { it.pageId }
    }

    /**
     * The highlight boxes for one page (FA-24c on-canvas search-result mode): one box per line that
     * contains a query word (whole-word, not a substring), page-space bounds ready for `pageToScreen`.
     */
    suspend fun pageHighlights(pageId: String, query: String): List<SearchHighlight> {
        val tokens = SearchQuery.tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val lowerTokens = tokens.map { it.lowercase() }
        return recognizedLineDao.searchContent(SearchQueries.contentOnPage(tokens, pageId))
            .filter { wordMatchCount(it.text, lowerTokens) > 0 }
            .map { SearchHighlight(it.minX, it.minY, it.maxX, it.maxY) }
    }

    /**
     * How many of [lowerTokens] match a word in [text] as a **word-start prefix** (FA-24c). The line is
     * tokenised the same way the query is ([SearchQuery.tokenize]); a token matches when some line word
     * *starts with* it — so "result" matches "results", while "is" matches "is"/"island" but NOT the
     * "is" inside "consistent"/"dentist" (word-boundary, not mid-word substring). Case-insensitive.
     */
    private fun wordMatchCount(text: String, lowerTokens: List<String>): Int {
        val lineWords = SearchQuery.tokenize(text).map { it.lowercase() }
        return lowerTokens.count { token -> lineWords.any { it.startsWith(token) } }
    }
}
