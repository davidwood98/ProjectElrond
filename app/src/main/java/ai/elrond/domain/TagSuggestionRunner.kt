package ai.elrond.domain

import ai.elrond.aibackend.TagSuggestionExtractor
import ai.elrond.data.SuggestionRepository

/**
 * Core of FA-24d Level 2 (AI new-tag suggestions), decoupled from Android/WorkManager/ink so it is
 * JVM-testable — the same split as [AutoExtractionRunner]. Aggregates a notebook's recognised text,
 * asks the model for genuinely NEW tag names, de-dups, and writes them as notebook-scoped
 * [PendingSuggestion]s of type [SuggestionType.TAG] which the tag UI renders as grey/gradient pills.
 *
 * Trigger-agnostic on purpose: the background save-job invokes [run] today, but a future on-demand
 * path (run when the tag picker opens, if the background cost is too high) can call the exact same
 * seam — nothing here assumes when it runs.
 *
 * Lifecycle (user choice: refresh when content changes):
 *  - Unchanged aggregate since the last run → skip the Anthropic call entirely (hash gate).
 *  - Changed aggregate → clear this notebook's *un-actioned* TAG suggestions and re-run. Accepted
 *    or dismissed rows stay (handled-once de-dup), so a resolved tag never re-appears.
 *
 * The only recognition/ink-touching step — turning a notebook's pages into one text blob — is
 * injected as [aggregateNotebookText] so tests supply canned text.
 */
class TagSuggestionRunner(
    private val aggregateNotebookText: suspend (notebookId: String) -> String,
    private val tagExtractor: TagSuggestionExtractor?,
    private val existingTagNames: suspend () -> List<String>,
    private val suggestionRepository: SuggestionRepository,
    private val loadHash: suspend (notebookId: String) -> String?,
    private val saveHash: suspend (notebookId: String, hash: String) -> Unit,
    private val maxSuggestions: Int = 5,
) {

    /**
     * @param anchorPageId a real page of the notebook, stored as the TAG rows' [PendingSuggestion.pageId]
     *   only to satisfy the table's page foreign key — all TAG queries key off [notebookId], so the
     *   value is otherwise unused. The triggering save's page is a natural choice.
     * @return the number of new TAG suggestions written.
     */
    suspend fun run(notebookId: String, anchorPageId: String): Int {
        if (tagExtractor == null) return 0
        val text = aggregateNotebookText(notebookId).trim()
        if (text.isBlank()) return 0

        // Skip-gate: identical aggregate since the last run → no model call, keep existing pills.
        val hash = text.hashCode().toString()
        if (hash == loadHash(notebookId)) return 0

        // Content changed → drop stale un-actioned suggestions, then re-evaluate. Handled rows stay.
        suggestionRepository.clearActiveTagSuggestions(notebookId)

        val existing = existingTagNames()
        val names = tagExtractor.extract(text, existing, maxSuggestions).getOrNull().orEmpty()
        // Ran (or attempted) the model this pass → remember the text so an unchanged next save skips.
        saveHash(notebookId, hash)
        if (names.isEmpty()) return 0

        val alreadySuggested = suggestionRepository.existingTagContents(notebookId)
        val accepted = mutableListOf<String>() // names kept this pass, for near-dup within the batch
        val pending = names.mapNotNull { name ->
            val norm = name.trim().lowercase()
            when {
                // Drop empties, exact re-suggestions, and near-duplicates of a name already kept this
                // pass ("revision"/"revisions"). Names that match an EXISTING tag are KEPT — the
                // provider classifies them as an endorsed-existing suggestion, not a new tag.
                norm.isEmpty() || norm in alreadySuggested -> null
                TagMatching.nearDuplicateOfAny(name, accepted) -> null
                else -> {
                    accepted += name.trim()
                    PendingSuggestion(
                        pageId = anchorPageId,
                        type = SuggestionType.TAG,
                        content = name.trim(),
                        x = 0f,
                        y = 0f,
                        notebookId = notebookId,
                    )
                }
            }
        }
        suggestionRepository.add(pending)
        return pending.size
    }
}
