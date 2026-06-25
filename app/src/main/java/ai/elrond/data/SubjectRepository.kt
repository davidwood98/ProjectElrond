package ai.elrond.data

import ai.elrond.domain.Subject
import ai.elrond.domain.SubjectPalette
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for the subject (folder) hierarchy and note→subject membership (FA-16).
 *
 * Single-subject model: a note files into at most one subject (or is unfiled). Deleting a subject
 * cascade-deletes its descendant subjects and un-files their notes (the notes themselves survive) —
 * the database foreign keys do this, so there is no manual recursion here.
 *
 * The clock, id, and colour generators are injectable so unit tests stay deterministic (mirrors
 * [NoteRepository]).
 */
class SubjectRepository(
    private val subjectDao: SubjectDao,
    private val noteSubjectDao: NoteSubjectDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val colorIdGenerator: () -> Int = { Random.nextInt(SubjectPalette.SIZE) },
) {

    fun observeSubjects(): Flow<List<Subject>> =
        subjectDao.observeAll().map { rows -> rows.map(SubjectEntity::toDomain) }

    /** Note→subject map (pageId → subjectId); a missing key means the note is unfiled. */
    fun observeNoteSubjects(): Flow<Map<String, String>> =
        noteSubjectDao.observeAll().map { rows -> rows.associate { it.pageId to it.subjectId } }

    /**
     * Creates a subject under [parentId] (null = root) with a random palette colour, placed last
     * among its siblings.
     */
    suspend fun createSubject(parentId: String?, name: String): Subject {
        val now = clock()
        val siblingMax = subjectDao.getAll()
            .filter { it.parentId == parentId }
            .maxOfOrNull { it.sortOrder } ?: -1L
        val entity = SubjectEntity(
            id = newId(),
            parentId = parentId,
            name = name.trim().ifEmpty { DEFAULT_NAME },
            colorId = SubjectPalette.normalize(colorIdGenerator()),
            sortOrder = siblingMax + 1,
            createdAt = now,
            modifiedAt = now,
        )
        subjectDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun renameSubject(id: String, name: String) {
        subjectDao.rename(id, name.trim().ifEmpty { DEFAULT_NAME }, clock())
    }

    suspend fun setColor(id: String, colorId: Int) {
        subjectDao.setColor(id, SubjectPalette.normalize(colorId), clock())
    }

    /** Deletes a subject; descendant subjects + memberships cascade via the foreign keys. */
    suspend fun deleteSubject(id: String) {
        subjectDao.deleteById(id)
    }

    /** Persists a new sibling order by writing each id's sortOrder to its position in the list. */
    suspend fun reorder(orderedSubjectIds: List<String>) {
        val now = clock()
        orderedSubjectIds.forEachIndexed { index, id ->
            subjectDao.setSortOrder(id, index.toLong(), now)
        }
    }

    /** Files [pageId] into [subjectId], or un-files it when [subjectId] is null. */
    suspend fun assignNote(pageId: String, subjectId: String?) {
        if (subjectId == null) {
            noteSubjectDao.deleteByPage(pageId)
        } else {
            noteSubjectDao.upsert(NoteSubjectEntity(pageId = pageId, subjectId = subjectId))
        }
    }

    suspend fun subjectForNote(pageId: String): String? = noteSubjectDao.getForPage(pageId)?.subjectId

    companion object {
        const val DEFAULT_NAME = "New subject"
    }
}
