package ai.elrond.data

import ai.elrond.domain.NotebookLink
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for on-canvas notebook link boxes (FA-24 Phase 1).
 *
 * Persistence follows the ai_notes model: [CanvasViewModel] owns the in-memory list (so links
 * ride the unified undo/redo snapshots) and writes it wholesale via [replaceForPage] on the
 * debounced autosave / onCleared flush. The clock and id generators are injectable so unit
 * tests stay deterministic (mirrors [SubjectRepository]); they are exposed because link
 * *creation* happens in the ViewModel, which needs the current page and viewport.
 */
class NotebookLinkRepository(
    private val linkDao: NotebookLinkDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    suspend fun loadForPage(pageId: String): List<NotebookLink> =
        linkDao.getForPage(pageId).map(NotebookLinkEntity::toDomain)

    suspend fun replaceForPage(pageId: String, links: List<NotebookLink>) {
        linkDao.replaceForPage(pageId, links.map { it.toEntity(pageId) })
    }

    /** Raw backlink rows for [notebookId]; the caller resolves source titles. */
    fun observeBacklinks(notebookId: String): Flow<List<BacklinkRow>> =
        linkDao.observeBacklinks(notebookId)

    /** Outgoing links from a notebook's pages (FA-24d) — reactive, for the Level 1 link-graph signal. */
    fun observeLinksFromNotebook(notebookId: String): Flow<List<NotebookLink>> =
        linkDao.observeLinksFromNotebook(notebookId).map { rows -> rows.map(NotebookLinkEntity::toDomain) }

    fun newLinkId(): String = newId()

    fun now(): Long = clock()
}
