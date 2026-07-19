package ai.elrond.domain

/** Where a tag suggestion came from — decides how its pill is rendered (FA-24d). */
enum class SuggestionOrigin {
    /** Level 1: an existing tag surfaced from Subject/link/content signals. Flat neutral pill. */
    EXISTING,

    /** Level 2: an AI-generated brand-new tag name. Low-opacity Leap-gradient FILL pill. */
    AI,

    /**
     * Level 2: the AI judged an EXISTING tag the best fit (semantically, where the deterministic
     * Level 1 signals didn't catch it). Rendered as a neutral existing-tag pill with a thin Leap
     * gradient BORDER — distinguishing "AI picked one of your tags" from "AI invented a new one".
     */
    AI_EXISTING,
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
