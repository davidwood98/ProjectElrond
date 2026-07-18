package ai.elrond.presentation

import ai.elrond.data.TagRepository
import ai.elrond.domain.SuggestedTag
import ai.elrond.domain.Tag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Notebook tags (FA-24 Phase 2), shared by BOTH tagging surfaces — the Library card ⋮ menu and
 * the editor header's tag row — through the one [TagRepository].
 *
 * Instantiated per screen (`hiltViewModel()` scopes to the nav back-stack entry): tag DATA is
 * shared through the singleton repository + Room's invalidation tracker, while the ephemeral
 * per-pill untag-window state below is deliberately per-surface UI state. Do not "fix" this
 * into a shared singleton ViewModel.
 *
 * **Untag window**: the second tap on a pill calls [beginUntag] — the pill greys out in place
 * for [UNTAG_WINDOW_MS] before the membership row is actually deleted; tapping it again during
 * the window ([cancelUntag]) cancels with no DB write ever having happened. Implemented as one
 * cancellable coroutine per pill (a Job map), never a blocking UI state — the row stays
 * interactive elsewhere throughout.
 */
@HiltViewModel
class TagViewModel @Inject constructor(
    private val tagRepository: TagRepository,
    private val tagSuggestionProvider: TagSuggestionProvider,
) : ViewModel() {

    init {
        // Sweep orphans left by paths that bypass removeTag (e.g. a notebook deletion's FK
        // cascade), so the selection menu never lists a tag no notebook carries; also repair
        // legacy dark-shade colours the pill text is unreadable on (FA-24 device feedback).
        // Re-run by [pruneOrphans] each time a picker opens.
        pruneOrphans()
    }

    /** Called when a tag picker opens — clears orphans + repairs unreadable legacy colours. */
    fun pruneOrphans() {
        viewModelScope.launch {
            runCatching { tagRepository.pruneOrphans() }
            runCatching { tagRepository.repairUnreadableColors() }
        }
    }

    val tags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * All tags ordered most-used-first (then by name) — the tag picker's existing-tags list uses this
     * so adding a tag manually promotes the most common (FA-24d: replaces the removed Level 1
     * frequency-fallback *suggestion*, keeping the frequency signal where it doesn't duplicate pills).
     */
    val tagsByFrequency: StateFlow<List<Tag>> = combine(
        tagRepository.observeTags(),
        tagRepository.observeNotebookTags(),
    ) { tags, notebookTags ->
        val counts = notebookTags.values.flatten().groupingBy { it.id }.eachCount()
        tags.sortedWith(compareByDescending<Tag> { counts[it.id] ?: 0 }.thenBy { it.name.lowercase() })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** notebookId → its tags; the Library grid needs every notebook's tags at once. */
    val notebookTags: StateFlow<Map<String, List<Tag>>> = tagRepository.observeNotebookTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Single-notebook view for the editor header. */
    fun tagsFor(notebookId: String): Flow<List<Tag>> =
        notebookTags.map { it[notebookId].orEmpty() }

    /**
     * Level 1 + Level 2 tag suggestions for a notebook (FA-24d), cached per notebook so both the
     * editor header and the picker share one live query. Tapping a pill calls [acceptSuggestion].
     */
    private val suggestionFlows = mutableMapOf<String, StateFlow<List<SuggestedTag>>>()

    fun suggestionsFor(notebookId: String): StateFlow<List<SuggestedTag>> =
        suggestionFlows.getOrPut(notebookId) {
            tagSuggestionProvider.observe(notebookId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
        }

    /** Commit a suggested tag (both tiers) via the same get-or-create/assign path as manual entry. */
    fun acceptSuggestion(notebookId: String, suggestion: SuggestedTag) {
        viewModelScope.launch { tagSuggestionProvider.accept(notebookId, suggestion) }
    }

    /** Pills currently greyed-out/counting down (as "$notebookId:$tagId" keys). */
    private val pendingJobs = mutableMapOf<String, Job>()
    private val _pendingRemovalKeys = MutableStateFlow<Set<String>>(emptySet())
    val pendingRemovalKeys: StateFlow<Set<String>> = _pendingRemovalKeys.asStateFlow()

    /** [pendingRemovalKeys] scoped to one notebook as bare tag ids — keeps key parsing out of the UI. */
    fun pendingRemovalTagIdsFor(notebookId: String): Flow<Set<String>> =
        _pendingRemovalKeys.map { keys ->
            val prefix = "$notebookId:"
            keys.filter { it.startsWith(prefix) }.mapTo(mutableSetOf()) { it.removePrefix(prefix) }
        }

    fun createAndAssignTag(notebookId: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val tag = tagRepository.createTag(name)
            tagRepository.assignTag(notebookId, tag.id)
        }
    }

    fun assignTag(notebookId: String, tagId: String) {
        viewModelScope.launch { tagRepository.assignTag(notebookId, tagId) }
    }

    /** Immediate removal — the picker's explicit untick (no undo window in a modal dialog). */
    fun removeTag(notebookId: String, tagId: String) {
        // An immediate removal supersedes any pending window for the same pill.
        cancelUntag(notebookId, tagId)
        viewModelScope.launch { tagRepository.removeTag(notebookId, tagId) }
    }

    /** Starts the 2s cancellable grey-out for a pill; no DB write until the window elapses. */
    fun beginUntag(notebookId: String, tagId: String) {
        val key = pillKey(notebookId, tagId)
        if (key in pendingJobs) return // idempotent while already pending
        _pendingRemovalKeys.update { it + key }
        pendingJobs[key] = viewModelScope.launch {
            delay(UNTAG_WINDOW_MS)
            runCatching { tagRepository.removeTag(notebookId, tagId) }
            pendingJobs.remove(key)
            _pendingRemovalKeys.update { it - key }
        }
    }

    /** Tapping the greyed pill during the window: cancel — the removal never reached the DB. */
    fun cancelUntag(notebookId: String, tagId: String) {
        val key = pillKey(notebookId, tagId)
        pendingJobs.remove(key)?.cancel()
        _pendingRemovalKeys.update { it - key }
    }

    override fun onCleared() {
        // Flush, don't drop: the user committed to the removal on the second tap; leaving the
        // screen mid-window must not silently cancel it. Same outlives-the-ViewModel flushScope
        // precedent as CanvasViewModel's onCleared autosave flush.
        pendingJobs.forEach { (key, job) ->
            job.cancel()
            val notebookId = key.substringBefore(':')
            val tagId = key.substringAfter(':')
            flushScope.launch { runCatching { tagRepository.removeTag(notebookId, tagId) } }
        }
        pendingJobs.clear()
        super.onCleared()
    }

    private fun pillKey(notebookId: String, tagId: String) = "$notebookId:$tagId"

    companion object {
        const val UNTAG_WINDOW_MS = 2_000L

        /** Survives ViewModel clear so an in-flight untag flush can't be lost mid-write. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
