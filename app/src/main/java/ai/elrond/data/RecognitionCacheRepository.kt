package ai.elrond.data

import ai.elrond.domain.recognizedLineKey

/**
 * Read-only seam over the recognition cache (FA-24b): the ViewModel's per-line lookup. A minimal
 * interface so JVM tests can fake it without a DAO; [RecognitionCacheRepository] is the production
 * implementation.
 */
interface RecognitionCache {
    /** Cached text for the line with these ordered stroke ids, or null on a miss. */
    suspend fun textForLine(pageId: String, orderedStrokeIds: List<String>): String?
}

/**
 * Persistent recognition cache (FA-24b): recognized handwriting lines keyed by their ordered
 * [ai.elrond.domain.CanvasStroke] ids, so AI features assemble page context without re-running
 * ML Kit on unchanged ink. Shared as a `@Singleton` because the background worker and the
 * ViewModel use separate recognizer instances but must see one cache.
 *
 * The write/sync path ([buildRecognizedLinesCached]) drives [getForPage]/[upsertAll]/[deleteByIds];
 * the read path ([textForLine]) is the ViewModel's per-line lookup (via [RecognitionCache]).
 */
class RecognitionCacheRepository(
    private val dao: RecognizedLineDao,
) : RecognitionCache {
    suspend fun getForPage(pageId: String): List<RecognizedLineEntity> = dao.getForPage(pageId)

    suspend fun upsertAll(rows: List<RecognizedLineEntity>) {
        if (rows.isNotEmpty()) dao.upsertAll(rows)
    }

    suspend fun deleteByIds(ids: List<String>) {
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    /** Cached text for the line with these ordered stroke ids, or null on a miss. */
    override suspend fun textForLine(pageId: String, orderedStrokeIds: List<String>): String? =
        dao.textForId(recognizedLineKey(pageId, orderedStrokeIds))
}
