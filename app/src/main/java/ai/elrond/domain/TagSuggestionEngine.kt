package ai.elrond.domain

/**
 * Level 1 tag suggestions (FA-24d): existing tags surfaced for a notebook from signals against data
 * that already exists — NO model call, no persistence. A pure function so every signal's ranking is
 * unit-testable in isolation.
 *
 * Signals, in priority order (doc FA-24d):
 *  1. **Same-Subject co-occurrence** — tags on sibling notebooks in this notebook's Subject.
 *  2. **Link-graph co-occurrence** — tags on notebooks this one links to (FA-24a `notebook_links`).
 *  3. **Content/title-word match** — an existing tag's name appears as a whole word in the notebook's
 *     cached recognised text (FA-24b `recognized_lines`) or its title.
 *
 * **Relevance-only (FA-24d device feedback):** the original "frequency fallback" (most-used tags when
 * the signals above were empty) was removed — on a blank, unfiled, untitled, empty notebook it just
 * dumped the whole registry, duplicating the picker's tag list. A notebook with no identity now gets
 * NO Level 1 suggestions; frequency instead orders the picker's *existing*-tags list (see
 * `TagViewModel.tagsByFrequency`). [usageCounts] survives only as a deterministic tie-break.
 *
 * Within a co-occurrence tier, candidates are ordered by how often they co-occur (the repeat count
 * in the id list), then by global [usageCounts], then name. Already-assigned tags are never
 * suggested. The result is de-duplicated across tiers and capped at [limit].
 */
object TagSuggestionEngine {

    fun suggest(
        allTags: List<Tag>,
        assignedTagIds: Set<String>,
        sameSubjectTagIds: List<String>,
        linkedTagIds: List<String>,
        usageCounts: Map<String, Int>,
        contentWords: Set<String>,
        limit: Int = 5,
    ): List<Tag> {
        if (limit <= 0) return emptyList()
        val byId = allTags.associateBy { it.id }

        val relevance = LinkedHashSet<String>()
        relevance += rankByCoOccurrence(sameSubjectTagIds, usageCounts, byId, assignedTagIds)
        relevance += rankByCoOccurrence(linkedTagIds, usageCounts, byId, assignedTagIds)
        relevance += contentMatches(allTags, assignedTagIds, usageCounts, contentWords)

        return relevance.toList().mapNotNull { byId[it] }.take(limit)
    }

    /** Candidate ids from a co-occurrence list, ranked by co-occurrence count → usage → name. */
    private fun rankByCoOccurrence(
        ids: List<String>,
        usageCounts: Map<String, Int>,
        byId: Map<String, Tag>,
        assignedTagIds: Set<String>,
    ): List<String> {
        val counts = ids.filter { it in byId && it !in assignedTagIds }
            .groupingBy { it }.eachCount()
        return counts.keys.sortedWith(
            compareByDescending<String> { counts.getValue(it) }
                .thenByDescending { usageCounts[it] ?: 0 }
                .thenBy { byId.getValue(it).name.lowercase() },
        )
    }

    /** Tags whose name appears as a whole word (token) in the cached content. */
    private fun contentMatches(
        allTags: List<Tag>,
        assignedTagIds: Set<String>,
        usageCounts: Map<String, Int>,
        contentWords: Set<String>,
    ): List<String> {
        if (contentWords.isEmpty()) return emptyList()
        return allTags.filter { tag ->
            tag.id !in assignedTagIds && nameTokens(tag.name).let { it.isNotEmpty() && contentWords.containsAll(it) }
        }.sortedWith(
            compareByDescending<Tag> { usageCounts[it.id] ?: 0 }.thenBy { it.name.lowercase() },
        ).map { it.id }
    }

    /** Lowercased word tokens of a tag name (a multi-word tag matches only if all its words appear). */
    private fun nameTokens(name: String): Set<String> = TagMatching.words(name)

    /** Whole-word tokens of recognised text (+ title), for [contentWords]. */
    fun contentWordsOf(text: String): Set<String> = TagMatching.words(text)
}
