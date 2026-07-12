package ai.elrond.data

import ai.elrond.domain.Tag
import ai.elrond.domain.TagColor
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for notebook tags (FA-24 Phase 2) — the ONE shared surface both tagging UIs (the
 * Library card ⋮ menu and the editor header) call, so they can't drift. Tags are flat and
 * many-to-many, independent of the Subjects hierarchy. The id generator is injectable so unit
 * tests stay deterministic (mirrors [SubjectRepository]).
 */
class TagRepository(
    private val tagDao: TagDao,
    private val notebookTagDao: NotebookTagDao,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    fun observeTags(): Flow<List<Tag>> =
        tagDao.observeAll().map { rows -> rows.map(TagEntity::toDomain) }

    /** notebookId → its assigned tags (name-ordered); a missing key means untagged. */
    fun observeNotebookTags(): Flow<Map<String, List<Tag>>> =
        notebookTagDao.observeAllWithTag()
            .map { rows -> rows.groupBy(NotebookTagRow::notebookId) { it.toTag() } }

    /**
     * Creates a tag named [name] (trimmed), or returns the existing tag when the name is already
     * taken — names are unique, so "create" is really get-or-create. The colour is resolved from
     * the name ONCE here and stored (deterministic per name, never recomputed).
     */
    suspend fun createTag(name: String): Tag {
        val trimmed = name.trim()
        tagDao.getByName(trimmed)?.let { return it.toDomain() }
        val entity = TagEntity(id = newId(), name = trimmed, colorArgb = TagColor.forName(trimmed))
        tagDao.insert(entity)
        return entity.toDomain()
    }

    /** Idempotent: assigning an already-assigned tag is a no-op. */
    suspend fun assignTag(notebookId: String, tagId: String) {
        notebookTagDao.assign(NotebookTagEntity(notebookId = notebookId, tagId = tagId))
    }

    /** Removing the last membership also erases the tag itself (no orphans in the menu). */
    suspend fun removeTag(notebookId: String, tagId: String) {
        notebookTagDao.remove(notebookId, tagId)
        tagDao.deleteOrphans()
    }

    /**
     * Sweeps tags orphaned by paths that bypass [removeTag] — chiefly a notebook deletion, whose
     * FK cascade clears memberships inside SQLite. Called on tagging-surface start/open.
     */
    suspend fun pruneOrphans() {
        tagDao.deleteOrphans()
    }
}
