package ai.elrond.domain

/** Where a tag suggestion came from — decides how its pill is rendered (FA-24d). */
enum class SuggestionOrigin {
    /** Level 1: an existing tag surfaced from Subject/link/frequency/content signals. Flat neutral pill. */
    EXISTING,

    /** Level 2: an AI-generated brand-new tag name. Low-opacity Leap-gradient pill. */
    AI,
}

/**
 * A tag offered to the user (FA-24d), rendered as a "+"-prefixed suggestion pill. Tapping it commits
 * the tag through the same `createTag`/`assignTag` path manual entry uses, so an accepted suggestion
 * is indistinguishable from a hand-typed tag afterward.
 *
 * @param tag the existing [Tag] for [SuggestionOrigin.EXISTING]; null for an AI new-tag name.
 * @param suggestionId the backing `PendingSuggestion` id for [SuggestionOrigin.AI] (to mark handled
 *        on accept); null for the stateless Level 1 suggestions.
 */
data class SuggestedTag(
    val origin: SuggestionOrigin,
    val name: String,
    val tag: Tag? = null,
    val suggestionId: String? = null,
)
