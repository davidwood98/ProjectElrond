package ai.elrond.data

import ai.elrond.domain.CanvasStroke
import ai.elrond.domain.recognizedLineKey
import android.content.Context
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cache-sync behaviour of [buildRecognizedLinesCached] against a real in-memory Room cache
 * (Robolectric). The ink-touching seams (grouping, bounds, recognizable filter) are faked so the
 * hit-reuse / miss-recognition / stale-delete logic is exercised without ink natives.
 */
@RunWith(RobolectricTestRunner::class)
class BuildRecognizedLinesCachedTest {

    private lateinit var db: ElrondDatabase
    private lateinit var cache: RecognitionCacheRepository

    private val dummyStroke = mockk<Stroke>()
    private fun cs(id: String) = CanvasStroke(id = id, stroke = dummyStroke)

    private class CountingRecognizer(private val text: String) : HandwritingRecognizer {
        var calls = 0
        override suspend fun recognize(strokes: List<Stroke>): Result<String> {
            calls++
            return Result.success(text)
        }
    }

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java).allowMainThreadQueries().build()
        db.notebookDao().insert(NotebookEntity(id = "nb1", name = "N", createdAt = 0))
        db.notePageDao().insert(
            NotePageEntity(id = "p1", notebookId = "nb1", customTitle = "Note", createdAt = 0, modifiedAt = 0),
        )
        cache = RecognitionCacheRepository(db.recognizedLineDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `hits reuse cached text, misses recognize, stale rows are deleted`() = runTest {
        val lineA = listOf(cs("a1"), cs("a2")) // cached
        val lineB = listOf(cs("b1")) // miss
        val keyA = recognizedLineKey("p1", listOf("a1", "a2"))
        val keyB = recognizedLineKey("p1", listOf("b1"))
        val staleKey = recognizedLineKey("p1", listOf("c1")) // no longer on the page

        // Seed the cache: line A already recognized, plus a stale line C.
        cache.upsertAll(
            listOf(
                RecognizedLineEntity(keyA, "p1", "a1,a2", "cached A", 0f, 0f, 1f, 1f, 1L),
                RecognizedLineEntity(staleKey, "p1", "c1", "stale C", 0f, 0f, 1f, 1f, 1L),
            ),
        )

        val recognizer = CountingRecognizer("RECOGNIZED B")
        val result = buildRecognizedLinesCached(
            pageId = "p1",
            strokes = lineA + lineB,
            recognizer = recognizer,
            cache = cache,
            recognizableInk = { true },
            groupLines = { listOf(lineA, lineB) },
            boundsOf = { floatArrayOf(5f, 6f, 7f, 8f) },
            now = { 100L },
        )

        // Only the miss (line B) is recognized; line A reuses its cached text.
        assertEquals(1, recognizer.calls)
        assertEquals(listOf("cached A", "RECOGNIZED B"), result.map { it.text })
        // Fresh bounds are applied to every returned line (a lasso move keeps the key, refreshes bounds).
        assertEquals(5f, result.first().minX)

        val cached = cache.getForPage("p1").associateBy { it.id }
        assertEquals("cached A", cached[keyA]?.text) // text preserved
        assertEquals(5f, cached[keyA]?.minX) // bounds refreshed
        assertEquals("RECOGNIZED B", cached[keyB]?.text) // miss recognized + cached
        assertNull("stale line evicted", cached[staleKey]) // stale row deleted
        assertEquals(2, cached.size)
    }

    @Test
    fun `no recognizable strokes clears the page cache and returns empty`() = runTest {
        val staleKey = recognizedLineKey("p1", listOf("x1"))
        cache.upsertAll(listOf(RecognizedLineEntity(staleKey, "p1", "x1", "gone", 0f, 0f, 1f, 1f, 1L)))

        val recognizer = CountingRecognizer("unused")
        val result = buildRecognizedLinesCached(
            pageId = "p1",
            strokes = listOf(cs("x1")),
            recognizer = recognizer,
            cache = cache,
            recognizableInk = { false }, // nothing recognizable (e.g. only highlighter left)
            groupLines = { error("should not group when nothing is recognizable") },
            boundsOf = { floatArrayOf(0f, 0f, 0f, 0f) },
            now = { 100L },
        )

        assertEquals(emptyList<String>(), result.map { it.text })
        assertEquals(0, recognizer.calls)
        assertEquals(emptyList<RecognizedLineEntity>(), cache.getForPage("p1"))
    }
}
