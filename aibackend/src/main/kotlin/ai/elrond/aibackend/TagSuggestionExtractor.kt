package ai.elrond.aibackend

/**
 * Proposes NEW tag names for a notebook from its aggregated handwritten content (FA-24d Level 2).
 *
 * Deliberately independent of the app layer and of any trigger: the background save-job calls it
 * today, but a future on-demand path (run when the tag picker opens, if the background cost is too
 * high) can call the exact same seam with no changes here.
 */
interface TagSuggestionExtractor {
    /**
     * @param noteContent aggregated recognized text across all of a notebook's pages.
     * @param existingTags tag names already in use anywhere in the app — passed as vocabulary so the
     *        model proposes genuinely NEW tags rather than re-suggesting ones that already exist
     *        (an existing-tag match is Level 1's job, not this one).
     * @param maxSuggestions cap on how many names to return.
     * @return short, lowercase-ish topical tag names for the notebook; empty when nothing fits.
     */
    suspend fun extract(
        noteContent: String,
        existingTags: List<String>,
        maxSuggestions: Int = 5,
    ): Result<List<String>>
}
