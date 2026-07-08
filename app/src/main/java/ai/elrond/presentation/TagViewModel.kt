package ai.elrond.presentation

import ai.elrond.data.TagRepository
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
) : ViewModel() {

    val tags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** notebookId → its tags; the Library grid needs every notebook's tags at once. */
    val notebookTags: StateFlow<Map<String, List<Tag>>> = tagRepository.observeNotebookTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Single-notebook view for the editor header. */
    fun tagsFor(notebookId: String): Flow<List<Tag>> =
        notebookTags.map { it[notebookId].orEmpty() }

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
