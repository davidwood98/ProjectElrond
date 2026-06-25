package ai.elrond.presentation

import ai.elrond.data.SettingsRepository
import ai.elrond.data.SubjectRepository
import ai.elrond.domain.Subject
import ai.elrond.domain.SubjectNode
import ai.elrond.domain.SubjectTree
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Subjects (folder hierarchy) feature (FA-16): the sidebar tree, expand/collapse + the
 * selected-subject filter (both persisted in DataStore), full subject CRUD + drag-to-reorder, and
 * the note→subject assignment used by note cards and the breadcrumb.
 *
 * Single-subject model: a note files into at most one subject; [noteSubjects] maps pageId → its one
 * subjectId. The tree, path/breadcrumb, and reorder maths are the pure [SubjectTree] helpers.
 */
@HiltViewModel
class SubjectViewModel @Inject constructor(
    private val subjectRepository: SubjectRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    val subjects: StateFlow<List<Subject>> = subjectRepository.observeSubjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val subjectsById: StateFlow<Map<String, Subject>> = subjects
        .map { list -> list.associateBy(Subject::id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The sidebar tree (roots → children), siblings sorted by sortOrder. */
    val tree: StateFlow<List<SubjectNode>> = subjects
        .map { SubjectTree.build(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** pageId → its (single) subjectId; a missing key means the note is unfiled. */
    val noteSubjects: StateFlow<Map<String, String>> = subjectRepository.observeNoteSubjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val expandedIds: StateFlow<Set<String>> = settings.expandedSubjectIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** The selected subject filtering the grid; null = All Notes. A stale id resolves to null below. */
    val selectedSubjectId: StateFlow<String?> = combine(settings.selectedSubjectId, subjectsById) { id, byId ->
        id?.takeIf { byId.containsKey(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ancestry path (root → selected) for the breadcrumb tab bar; empty when no subject is selected. */
    val selectedPath: StateFlow<List<Subject>> = combine(selectedSubjectId, subjectsById) { id, byId ->
        SubjectTree.pathTo(id, byId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Subject CRUD ---

    fun createSubject(parentId: String?, name: String = SubjectRepository.DEFAULT_NAME) {
        viewModelScope.launch {
            subjectRepository.createSubject(parentId, name)
            // Reveal the new child by expanding its parent.
            if (parentId != null) settings.setSubjectExpanded(parentId, true)
        }
    }

    fun renameSubject(id: String, name: String) {
        viewModelScope.launch { subjectRepository.renameSubject(id, name) }
    }

    fun setColor(id: String, colorId: Int) {
        viewModelScope.launch { subjectRepository.setColor(id, colorId) }
    }

    fun deleteSubject(id: String) {
        viewModelScope.launch {
            // If the deleted subject is the selected one (or an ancestor of it), reset to All Notes —
            // the descendant + its membership are cascade-deleted, so the selection would dangle.
            // Read fresh snapshots (not the WhileSubscribed .value caches, which are empty with no
            // active collector) so the guard is deterministic regardless of what the UI is observing.
            settings.selectedSubjectId.first()?.let { sel ->
                val byId = currentSubjects().associateBy { it.id }
                if (SubjectTree.pathTo(sel, byId).any { it.id == id }) {
                    settings.setSelectedSubjectId(null)
                }
            }
            subjectRepository.deleteSubject(id)
        }
    }

    /** A fresh subject snapshot from the repository (not the WhileSubscribed cache); used by the
     *  imperative action handlers that may run with no active collector. */
    private suspend fun currentSubjects(): List<Subject> = subjectRepository.observeSubjects().first()

    // --- Tree interaction ---

    fun toggleExpanded(id: String) {
        viewModelScope.launch { settings.setSubjectExpanded(id, id !in expandedIds.value) }
    }

    /**
     * Expands every subject on the path down to [subjectId] (inclusive) so a note filed in it becomes
     * visible — backs the Quick Nav "locate current note" button. Reads a fresh subject snapshot so
     * the path is correct regardless of what the UI is collecting.
     */
    fun expandToSubject(subjectId: String) {
        viewModelScope.launch {
            val byId = currentSubjects().associateBy { it.id }
            val pathIds = SubjectTree.pathTo(subjectId, byId).map { it.id }.toSet()
            settings.expandSubjects(pathIds)
        }
    }

    fun selectSubject(id: String?) {
        viewModelScope.launch { settings.setSelectedSubjectId(id) }
    }

    /**
     * Drag-to-reorder: move [movedId] one step up/down among its same-parent siblings, then persist.
     * Resolves the subject + its siblings from a fresh repository snapshot (not the WhileSubscribed
     * caches) and works by id + direction, so it's correct regardless of what the UI is observing and
     * never depends on a UI-captured index that may be stale after the list re-emits.
     */
    fun moveSubject(movedId: String, up: Boolean) {
        viewModelScope.launch {
            val all = currentSubjects()
            val moved = all.firstOrNull { it.id == movedId } ?: return@launch
            val siblings = all.filter { it.parentId == moved.parentId }
            val newOrder = SubjectTree.move(siblings, movedId, up)
            subjectRepository.reorder(newOrder.map(Subject::id))
        }
    }

    // --- Note assignment ---

    /** Files [pageId] into [subjectId] (or un-files it when null). */
    fun assignNote(pageId: String, subjectId: String?) {
        viewModelScope.launch { subjectRepository.assignNote(pageId, subjectId) }
    }
}
