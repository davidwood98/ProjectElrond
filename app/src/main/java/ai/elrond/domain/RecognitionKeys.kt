package ai.elrond.domain

import java.security.MessageDigest

/**
 * Stable cache key for a recognized handwriting line (FA-24b): the SHA-256 hex of the page id
 * plus the line's **ordered** [CanvasStroke] ids. Ink `Stroke`s have no value identity and are
 * rebuilt on transform, but `CanvasStroke.id` is stable across transforms and persistence — so
 * any membership change (stroke added/erased, lines merged/split) yields a different key, giving
 * automatic invalidation. A lasso move keeps the ids ⇒ same key ⇒ cached text stays valid. This
 * is the single key function used by both the writer (worker) and the reader (ViewModel).
 */
fun recognizedLineKey(pageId: String, strokeIds: List<String>): String {
    val payload = "$pageId|${strokeIds.joinToString(",")}"
    val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

/**
 * Splits the current line keys against what the cache already holds: `first` = keys to recognize
 * (present now, not cached), `second` = stale keys to evict (cached, no longer present). Pure set
 * diff — the tested core of cache invalidation.
 */
fun recognitionCacheDiff(
    currentKeys: Set<String>,
    cachedKeys: Set<String>,
): Pair<Set<String>, Set<String>> =
    (currentKeys - cachedKeys) to (cachedKeys - currentKeys)
