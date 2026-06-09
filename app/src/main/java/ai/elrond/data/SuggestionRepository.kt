package ai.elrond.data

import ai.elrond.extract.PendingSuggestion
import ai.elrond.extract.SuggestionType
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists background-extracted suggestions awaiting the user's on-canvas Yes/No
 * decision (FA-2). This is the channel between the background [ai.elrond.extract]
 * worker and the canvas UI — it survives the process boundary and app restarts.
 *
 * The clock and id generator are injectable for deterministic unit tests.
 */
class SuggestionRepository(
    private val dao: PendingSuggestionDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    /** Active (not-yet-decided) suggestions for a page. */
    fun observePending(pageId: String): Flow<List<PendingSuggestion>> =
        dao.observeForPage(pageId).map { rows -> rows.map { it.toDomain() } }

    /** Normalized contents already suggested for this page (incl. dismissed) — for de-dup. */
    suspend fun existingContents(pageId: String): Set<String> =
        dao.contentsForPage(pageId).map { it.trim().lowercase() }.toSet()

    /** Type-namespaced keys ("TODO:content" / "EVENT:content") already suggested for this page. */
    suspend fun existingTypedContents(pageId: String): Set<String> =
        dao.typedContentsForPage(pageId).map { "${it.type}:${it.content.trim().lowercase()}" }.toSet()

    suspend fun add(suggestions: List<PendingSuggestion>) {
        if (suggestions.isEmpty()) return
        val now = clock()
        dao.insertAll(suggestions.map { it.toEntity(now) })
    }

    suspend fun get(id: String): PendingSuggestion? = dao.getById(id)?.toDomain()

    /** Accepted/committed — remove the suggestion. */
    suspend fun remove(id: String) = dao.deleteById(id)

    /** Rejected — keep the row (dismissed) so the same item isn't re-suggested next save. */
    suspend fun dismiss(id: String) = dao.markDismissed(id)

    private fun PendingSuggestionEntity.toDomain() = PendingSuggestion(
        id = id,
        pageId = pageId,
        type = runCatching { SuggestionType.valueOf(type) }.getOrDefault(SuggestionType.TODO),
        content = content,
        x = x,
        y = y,
        dueAtMillis = dueAtMillis,
        priority = priority,
        startMillis = startMillis,
        endMillis = endMillis,
        location = location,
    )

    private fun PendingSuggestion.toEntity(now: Long) = PendingSuggestionEntity(
        id = id.ifEmpty { newId() },
        pageId = pageId,
        type = type.name,
        content = content,
        dueAtMillis = dueAtMillis,
        priority = priority,
        startMillis = startMillis,
        endMillis = endMillis,
        location = location,
        x = x,
        y = y,
        dismissed = false,
        createdAt = now,
    )
}
