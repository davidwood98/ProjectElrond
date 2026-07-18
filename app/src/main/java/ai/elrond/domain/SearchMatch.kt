package ai.elrond.domain

/**
 * Pure FA-24c relevance merge: combines the three per-notebook match sets (title / tag / content)
 * into the ranked, scope-filtered notebook-id order that drives the tile grid. Not a UNION pretending
 * they're the same data — each source contributes a tier, and content refines within its tier by a
 * caller-supplied relevance score.
 */
object SearchMatch {

    // Relevance tiers — a full-phrase title match is the strongest intent, then content by its score,
    // then a tag name match, then a partial (token) title match. A notebook takes its best source.
    // The wide gaps keep tiers from interleaving; content relevance refines within its own tier.
    private const val TIER_TITLE_FULL = 4000.0
    private const val TIER_CONTENT = 3000.0
    private const val TIER_TAG = 2000.0
    private const val TIER_TITLE_PARTIAL = 1000.0

    /**
     * Merges the three per-notebook match sets into the ranked, scope-filtered notebook-id order.
     * [contentRelevance] maps a notebook to its best content score (higher = better). Ties break by
     * [naturalOrder] (the tab's own ordering) then id, so equal-tier tiles keep a stable order.
     */
    fun rankedNotebookIds(
        scopeIds: Set<String>,
        titleFull: Set<String>,
        titlePartial: Set<String>,
        tagMatches: Set<String>,
        contentRelevance: Map<String, Double>,
        naturalOrder: List<String> = emptyList(),
    ): List<String> {
        val orderIndex = naturalOrder.withIndex().associate { (i, id) -> id to i }
        val candidates = (titleFull + titlePartial + tagMatches + contentRelevance.keys)
            .filter { it in scopeIds }
        return candidates
            .map { id ->
                val score = maxOf(
                    if (id in titleFull) TIER_TITLE_FULL else Double.NEGATIVE_INFINITY,
                    if (id in contentRelevance) TIER_CONTENT + contentRelevance.getValue(id) else Double.NEGATIVE_INFINITY,
                    if (id in tagMatches) TIER_TAG else Double.NEGATIVE_INFINITY,
                    if (id in titlePartial) TIER_TITLE_PARTIAL else Double.NEGATIVE_INFINITY,
                )
                id to score
            }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenBy { orderIndex[it.first] ?: Int.MAX_VALUE }
                    .thenBy { it.first },
            )
            .map { it.first }
    }
}
