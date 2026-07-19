package ai.elrond.domain

/**
 * Shared tag text matching (FA-24d). One home for tokenisation + near-duplicate detection so the
 * Level 1 engine, the Level 2 runner, and the merge provider all agree on what "the same tag" means.
 */
object TagMatching {

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercased whole-word tokens (no stemming) — for exact content-word matching. */
    fun words(text: String): Set<String> =
        text.lowercase().split(NON_WORD).filter { it.isNotBlank() }.toSet()

    /** Crude singular/plural fold: drop a trailing 's' on words long enough to keep meaning. */
    private fun stem(word: String): String =
        if (word.length > 3 && word.endsWith("s")) word.dropLast(1) else word

    private fun stemmedTokens(name: String): Set<String> = words(name).mapTo(mutableSetOf()) { stem(it) }

    /**
     * The SAME tag, tolerating case + singular/plural only — "revision"/"revisions" match, but
     * "graph"/"spider graph" do NOT. Use this to decide whether an AI suggestion IS an existing tag
     * (so a more-specific new tag like "spider graph" isn't swallowed by a generic "graph").
     */
    fun isSameTag(a: String, b: String): Boolean {
        val sa = stemmedTokens(a)
        val sb = stemmedTokens(b)
        return sa.isNotEmpty() && sa == sb
    }

    /**
     * Near-duplicates: [isSameTag] PLUS word-subset either way, so "settings"/"user settings" also
     * collapse. Deliberately aggressive — use only to avoid re-offering a variant of a tag the
     * notebook ALREADY HAS, never to reclassify a genuinely more-specific suggestion.
     */
    fun isNearDuplicate(a: String, b: String): Boolean {
        val sa = stemmedTokens(a)
        val sb = stemmedTokens(b)
        if (sa.isEmpty() || sb.isEmpty()) return false
        return sa == sb || sa.containsAll(sb) || sb.containsAll(sa)
    }

    /** True if [name] is the same tag as any of [others] (case + plural only). */
    fun sameTagAsAny(name: String, others: Collection<String>): Boolean =
        others.any { isSameTag(name, it) }

    /** True if [name] near-duplicates any of [existing] (aggressive; for already-assigned tags). */
    fun nearDuplicateOfAny(name: String, existing: Collection<String>): Boolean =
        existing.any { isNearDuplicate(name, it) }
}
