package ai.elrond.domain

/**
 * Splits a raw user query into search tokens (FA-24c). Splits on any run of non-letter/digit
 * characters (Unicode-aware) and drops empties, so punctuation and stray symbols never become search
 * terms or break the SQL `LIKE` matching. Lowercasing is unnecessary — SQLite `LIKE` is
 * case-insensitive for ASCII, and the tokens (letters/digits only) can't contain `LIKE` wildcards.
 */
object SearchQuery {
    fun tokenize(raw: String): List<String> =
        raw.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
}
