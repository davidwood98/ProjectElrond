package ai.elrond.data

import ai.elrond.ai.AiInkNote
import ai.elrond.notes.NoteEditDay
import ai.elrond.notes.Notebook
import ai.elrond.notes.NotePage
import androidx.ink.strokes.Stroke
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for notebooks, note pages, ink strokes, and AI response notes.
 *
 * The clock and id generator are injectable for deterministic unit tests.
 */
class NoteRepository(
    private val notebookDao: NotebookDao,
    private val pageDao: NotePageDao,
    private val strokeDao: StrokeDao,
    private val aiNoteDao: AiNoteDao,
    private val editEventDao: PageEditEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    // --- Notebooks ---

    fun observeNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeAll().map { entities -> entities.map(NotebookEntity::toDomain) }

    suspend fun createNotebook(name: String): Notebook {
        val entity = NotebookEntity(id = newId(), name = name, createdAt = clock())
        notebookDao.insert(entity)
        return entity.toDomain()
    }

    /** Returns the first notebook, creating the default one on first launch. */
    suspend fun ensureDefaultNotebook(): Notebook =
        notebookDao.first()?.toDomain() ?: createNotebook(DEFAULT_NOTEBOOK_NAME)

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
        val now = clock()
        pageDao.rename(pageId, title, now)
        recordEdit(pageId, now)
    }

    suspend fun getPage(pageId: String): NotePage? = pageDao.getById(pageId)?.toDomain()

    /** Deletes the page; its strokes cascade-delete via the foreign key. */
    suspend fun deletePage(pageId: String) {
        pageDao.deleteById(pageId)
    }

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
        recordEdit(pageId, now)
    }

    suspend fun loadStrokes(pageId: String): List<Stroke> =
        strokeDao.getForPage(pageId).map(StrokeSerialization::toStroke)

    /** Atomically rewrites the page's strokes — canvas auto-save (handles erase/undo too). */
    suspend fun replaceStrokes(pageId: String, strokes: List<Stroke>, isAiInk: Boolean = false) {
        val now = clock()
        strokeDao.replaceForPage(
            pageId,
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
        recordEdit(pageId, now)
    }

    /**
     * Lightweight stroke polylines for note-card thumbnails, normalized to 0..1.
     * Decodes stored points directly — no ink natives, cheap enough for lists.
     */
    suspend fun loadStrokePreview(pageId: String, maxStrokes: Int = PREVIEW_MAX_STROKES): List<List<Pair<Float, Float>>> {
        val polylines = strokeDao.getForPage(pageId)
            .take(maxStrokes)
            .map { StrokeSerialization.decodePoints(it.inputsJson) }
        return StrokePreviewNormalizer.normalize(polylines)
    }

    suspend fun clearStrokes(pageId: String) {
        val now = clock()
        strokeDao.deleteForPage(pageId)
        pageDao.touch(pageId, now)
        recordEdit(pageId, now)
    }

    // --- Per-day edit events (calendar created-vs-edited tracking) ---

    /** Records that [pageId] was edited on the local day of [now]; deduped per day by the DAO. */
    private suspend fun recordEdit(pageId: String, now: Long) {
        val editDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toEpochDay()
        editEventDao.insert(
            PageEditEventEntity(id = newId(), pageId = pageId, editDay = editDay, editedAt = now),
        )
    }

    /** Every recorded edit day across all pages — backs the calendar's edited indicators. */
    fun observeEditEvents(): Flow<List<NoteEditDay>> =
        editEventDao.observeAll().map { rows ->
            rows.map { NoteEditDay(pageId = it.pageId, date = LocalDate.ofEpochDay(it.editDay)) }
        }

    // --- AI response notes ---

    suspend fun loadAiNotes(pageId: String): List<AiInkNote> =
        aiNoteDao.getForPage(pageId).map(AiNoteEntity::toDomain)

    /** Atomically rewrites the page's AI notes (handles add / move / resize / remove). */
    suspend fun replaceAiNotes(pageId: String, notes: List<AiInkNote>) {
        val now = clock()
        aiNoteDao.replaceForPage(pageId, notes.map { it.toEntity(pageId, now) })
    }

    companion object {
        const val DEFAULT_NOTEBOOK_NAME = "My Notes"
        const val PREVIEW_MAX_STROKES = 60
    }
}
