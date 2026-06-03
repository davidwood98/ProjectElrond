package ai.elrond.data

import ai.elrond.notes.Notebook
import ai.elrond.notes.NotePage
import androidx.ink.strokes.Stroke
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for notebooks, note pages, and ink strokes.
 *
 * The clock and id generator are injectable for deterministic unit tests.
 */
class NoteRepository(
    private val notebookDao: NotebookDao,
    private val pageDao: NotePageDao,
    private val strokeDao: StrokeDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    // --- Notebooks ---

    fun observeNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeAll().map { entities -> entities.map(NotebookEntity::toDomain) }

    suspend fun createNotebook(name: String): Notebook {
        val entity = NotebookEntity(id = newId(), name = name, createdAt = clock())
        notebookDao.insert(entity)
        return entity.toDomain()
    }

    // --- Pages ---

    fun observePages(notebookId: String): Flow<List<NotePage>> =
        pageDao.observeByNotebook(notebookId).map { entities -> entities.map(NotePageEntity::toDomain) }

    /** All pages ordered by last edit — the "created X / last edited Y" timeline. */
    fun observeTimeline(): Flow<List<NotePage>> =
        pageDao.observeTimeline().map { entities -> entities.map(NotePageEntity::toDomain) }

    /** Creates a page; with no [customTitle] the page shows its timestamp-based title. */
    suspend fun createPage(notebookId: String, customTitle: String? = null): NotePage {
        val now = clock()
        val entity = NotePageEntity(
            id = newId(),
            notebookId = notebookId,
            customTitle = customTitle,
            createdAt = now,
            modifiedAt = now,
        )
        pageDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun renamePage(pageId: String, title: String?) {
        pageDao.rename(pageId, title, clock())
    }

    suspend fun getPage(pageId: String): NotePage? = pageDao.getById(pageId)?.toDomain()

    // --- Strokes ---

    suspend fun saveStrokes(pageId: String, strokes: List<Stroke>, isAiInk: Boolean = false) {
        if (strokes.isEmpty()) return
        val now = clock()
        strokeDao.insertAll(
            strokes.map { stroke ->
                StrokeSerialization.toEntity(
                    stroke = stroke,
                    id = newId(),
                    pageId = pageId,
                    createdAt = now,
                    isAiInk = isAiInk,
                )
            },
        )
        pageDao.touch(pageId, now)
    }

    suspend fun loadStrokes(pageId: String): List<Stroke> =
        strokeDao.getForPage(pageId).map(StrokeSerialization::toStroke)

    suspend fun clearStrokes(pageId: String) {
        strokeDao.deleteForPage(pageId)
        pageDao.touch(pageId, clock())
    }
}
