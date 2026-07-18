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
     * Two tag names are near-duplicates when, after lowercasing + singular/plural folding, their word
     * sets are equal OR one is a subset of the other — so "revision"/"revisions" and
     * "settings"/"user settings" both collapse. Empty-token names never match.
     */
    fun isNearDuplicate(a: String, b: String): Boolean {
        val sa = stemmedTokens(a)
        val sb = stemmedTokens(b)
        if (sa.isEmpty() || sb.isEmpty()) return false
        return sa == sb || sa.containsAll(sb) || sb.containsAll(sa)
    }

    /** True if [name] near-duplicates any of [existing]. */
    fun nearDuplicateOfAny(name: String, existing: Collection<String>): Boolean =
        existing.any { isNearDuplicate(name, it) }
}
