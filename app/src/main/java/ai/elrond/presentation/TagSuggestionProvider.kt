package ai.elrond.presentation

import ai.elrond.data.NoteRepository
import ai.elrond.data.NotebookLinkRepository
import ai.elrond.data.RecognitionCacheRepository
import ai.elrond.data.SubjectRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TagRepository
import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.SuggestedTag
import ai.elrond.domain.SuggestionOrigin
import ai.elrond.domain.Tag
import ai.elrond.domain.TagMatching
import ai.elrond.domain.TagSuggestionEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest

/**
 * Assembles tag suggestions for a notebook (FA-24d), merging the two tiers into one list the tag
 * UI renders as pills:
 *  - **Level 1** (existing tags) — a live, stateless [TagSuggestionEngine] query over Subject/link/
 *    frequency/content signals; recomputed reactively, never persisted.
 *  - **Level 2** (AI new tags) — the background-written `PendingSuggestion(TAG)` rows.
 *
 * Kept out of [TagViewModel] so the multi-repository gathering stays independently unit-testable and
 * the ViewModel stays thin. [accept] is the single commit path for BOTH tiers, so an accepted
 * suggestion goes through exactly the `createTag`/`assignTag` calls manual entry uses.
 */
@Singleton
class TagSuggestionProvider @Inject constructor(
    private val tagRepository: TagRepository,
    private val subjectRepository: SubjectRepository,
    private val notebookLinkRepository: NotebookLinkRepository,
    private val noteRepository: NoteRepository,
    private val recognitionCache: RecognitionCacheRepository,
    private val suggestionRepository: SuggestionRepository,
) {

    private data class Inputs(
        val allTags: List<Tag>,
        val notebookTags: Map<String, List<Tag>>,
        val noteSubjects: Map<String, String>,
        val aiPending: List<PendingSuggestion>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(notebookId: String, limit: Int = DEFAULT_LIMIT): Flow<List<SuggestedTag>> =
        combine(
            tagRepository.observeTags(),
            tagRepository.observeNotebookTags(),
            subjectRepository.observeNoteSubjects(),
            suggestionRepository.observeTagSuggestions(notebookId),
        ) { allTags, notebookTags, noteSubjects, aiPending ->
            Inputs(allTags, notebookTags, noteSubjects, aiPending)
        }.mapLatest { build(notebookId, limit, it) }

    private suspend fun build(notebookId: String, limit: Int, i: Inputs): List<SuggestedTag> {
        val assigned = i.notebookTags[notebookId].orEmpty()
        val assignedIds = assigned.map { it.id }.toSet()

        // Signal 1: sibling notebooks in the same Subject (single-subject model).
        val subjectId = i.noteSubjects[notebookId]
        val sameSubjectTagIds = if (subjectId == null) emptyList() else {
            i.noteSubjects.filter { it.value == subjectId && it.key != notebookId }.keys
                .flatMap { sib -> i.notebookTags[sib].orEmpty().map { it.id } }
        }

        // Signal 2: notebooks this one links to (page-scoped links → target notebooks' tags).
        val pageIds = noteRepository.pageIdsForNotebook(notebookId)
        val linkedTagIds = pageIds
            .flatMap { notebookLinkRepository.loadForPage(it) }
            .mapNotNull { it.targetNotebookId }
            .flatMap { target -> i.notebookTags[target].orEmpty().map { it.id } }

        // Signal 3: whole-word match against the notebook's content AND its (user-given) title, so a
        // named-but-empty notebook still gets title-relevant suggestions. usageCounts is only a
        // deterministic tie-break now (the old frequency fallback was removed — see the engine).
        val usageCounts = i.notebookTags.values.flatten().groupingBy { it.id }.eachCount()
        val text = pageIds
            .flatMap { recognitionCache.getForPage(it).map { line -> line.text } }
            .joinToString("\n")
        val title = noteRepository.getNotebookName(notebookId).orEmpty()
        val contentWords = TagSuggestionEngine.contentWordsOf("$title\n$text")

        val level1 = TagSuggestionEngine.suggest(
            allTags = i.allTags,
            assignedTagIds = assignedIds,
            sameSubjectTagIds = sameSubjectTagIds,
            linkedTagIds = linkedTagIds,
            usageCounts = usageCounts,
            contentWords = contentWords,
            limit = limit,
        ).map { SuggestedTag(origin = SuggestionOrigin.EXISTING, name = it.name, tag = it) }

        // Classify each AI (Level 2) row. It's an endorsed-EXISTING tag only when it's the SAME tag
        // (exact/plural) as one that exists — a more-specific suggestion like "spider graph" is NOT
        // swallowed by a generic existing "graph"; it stays a new tag. Drop a row that duplicates a
        // tag the notebook ALREADY HAS (aggressive subset match — no point re-offering a variant of
        // an assigned tag), or one Level 1 is already surfacing (same tag → Level 1 wins the tie).
        val assignedNames = assigned.map { it.name }
        val level1Names = level1.map { it.name }
        val level2 = i.aiPending.mapNotNull { row ->
            when {
                TagMatching.nearDuplicateOfAny(row.content, assignedNames) -> null
                TagMatching.sameTagAsAny(row.content, level1Names) -> null
                else -> {
                    val existing = i.allTags.firstOrNull { TagMatching.isSameTag(it.name, row.content) }
                    if (existing != null) {
                        SuggestedTag(SuggestionOrigin.AI_EXISTING, existing.name, tag = existing, suggestionId = row.id)
                    } else {
                        SuggestedTag(SuggestionOrigin.AI, row.content, suggestionId = row.id)
                    }
                }
            }
        }

        // AI suggestions FIRST so a text-heavy notebook's Level 1 matches can't starve them out of the
        // cap (the user sized the AI count deliberately); Level 1 fills the remaining slots. De-dup by
        // name, cap at [limit].
        val seen = mutableSetOf<String>()
        return (level2 + level1).filter { seen.add(it.name.trim().lowercase()) }.take(limit)
    }

    /**
     * Commits a suggestion via the SAME path manual tag entry uses (get-or-create then assign), so
     * the result is indistinguishable from a hand-typed tag. An AI (Level 2) suggestion is also
     * marked handled so it isn't re-proposed.
     */
    suspend fun accept(notebookId: String, suggestion: SuggestedTag) {
        val tagId = suggestion.tag?.id ?: tagRepository.createTag(suggestion.name).id
        tagRepository.assignTag(notebookId, tagId)
        suggestion.suggestionId?.let { suggestionRepository.markHandled(it) }
    }

    companion object {
        const val DEFAULT_LIMIT = 5
    }
}
