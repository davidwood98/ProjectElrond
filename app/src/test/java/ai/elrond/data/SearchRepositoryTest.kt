package ai.elrond.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * FA-24c search: title, tag and content matching, ranking, scope, and on-canvas highlights against a
 * real in-memory Room DB (Robolectric). Content search is plain `LIKE` over `recognized_lines`, so —
 * unlike the abandoned FTS5 approach — it runs under Robolectric with no fts5 module. The pure ranking
 * merge is also covered by `SearchMatchTest`.
 */
@RunWith(RobolectricTestRunner::class)
class SearchRepositoryTest {

    private lateinit var db: ElrondDatabase
    private lateinit var repo: SearchRepository

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = SearchRepository(db.notebookDao(), db.notebookTagDao(), db.recognizedLineDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun notebook(id: String, name: String) =
        db.notebookDao().insert(NotebookEntity(id = id, name = name, createdAt = 0))

    private suspend fun tag(notebookId: String, tagId: String, name: String) {
        db.tagDao().insert(TagEntity(id = tagId, name = name, colorArgb = 0))
        db.notebookTagDao().assign(NotebookTagEntity(notebookId = notebookId, tagId = tagId))
    }

    private suspend fun page(pageId: String, notebookId: String) =
        db.notePageDao().insert(
            NotePageEntity(id = pageId, notebookId = notebookId, customTitle = null, createdAt = 0, modifiedAt = 0),
        )

    private suspend fun line(pageId: String, text: String, top: Float = 0f) =
        db.recognizedLineDao().upsertAll(
            listOf(RecognizedLineEntity("$pageId-$text", pageId, "s", text, 0f, top, 10f, top + 10f, 0L)),
        )

    @Test
    fun `title full-phrase match ranks above a partial-token match`() = runTest {
        notebook("nb1", "Forward Kinematics")   // full phrase
        notebook("nb2", "Kinematics of gears")  // partial (one token)

        val ranked = repo.rankedNotebookIds("forward kinematics", setOf("nb1", "nb2"))
        assertEquals(listOf("nb1", "nb2"), ranked)
    }

    @Test
    fun `tag name match surfaces the notebook`() = runTest {
        notebook("nb1", "Untitled")
        notebook("nb2", "Untitled")
        tag("nb1", "t1", "physics")

        val ranked = repo.rankedNotebookIds("physics", setOf("nb1", "nb2"))
        assertEquals(listOf("nb1"), ranked)
    }

    @Test
    fun `results are limited to the scope set`() = runTest {
        notebook("nb1", "physics notes")
        notebook("nb2", "physics revision")

        val ranked = repo.rankedNotebookIds("physics", setOf("nb1")) // nb2 out of scope
        assertEquals(listOf("nb1"), ranked)
    }

    @Test
    fun `an empty or punctuation-only query matches nothing`() = runTest {
        notebook("nb1", "physics")
        assertTrue(repo.rankedNotebookIds("", setOf("nb1")).isEmpty())
        assertTrue(repo.rankedNotebookIds("  ,. ", setOf("nb1")).isEmpty())
    }

    // --- content search (LIKE over recognized_lines) ---

    @Test
    fun `handwritten content match surfaces a notebook with a plain title`() = runTest {
        notebook("nb1", "Untitled"); page("p1", "nb1"); line("p1", "the mitochondria")
        notebook("nb2", "Untitled"); page("p2", "nb2"); line("p2", "something else")

        // The bug this fixes: 'mitochondria' is only in the handwriting, not the title/tags.
        assertEquals(listOf("nb1"), repo.rankedNotebookIds("mitochondria", setOf("nb1", "nb2")))
    }

    @Test
    fun `content is matched case-insensitively`() = runTest {
        notebook("nb1", "Untitled"); page("p1", "nb1"); line("p1", "Hello there")
        assertEquals(listOf("nb1"), repo.rankedNotebookIds("hello", setOf("nb1")))
    }

    @Test
    fun `content matches whole words only, not substrings`() = runTest {
        // The bug: 'is' must match the word "is", not the "is" inside "consistent"/"dentist".
        notebook("nb1", "A"); page("p1", "nb1"); line("p1", "Hello my name is David")
        notebook("nb2", "B"); page("p2", "nb2"); line("p2", "we dont seem to be getting consistent AI results")
        notebook("nb3", "C"); page("p3", "nb3"); line("p3", "Book dentist appointment")

        assertEquals(listOf("nb1"), repo.rankedNotebookIds("is", setOf("nb1", "nb2", "nb3")))
    }

    @Test
    fun `content matches a word-start prefix but not a mid-word substring`() = runTest {
        notebook("nb1", "A"); page("p1", "nb1"); line("p1", "these are the results")   // 'result' -> 'results'
        notebook("nb2", "B"); page("p2", "nb2"); line("p2", "consistent dentist")       // no word starts with 'result'
        assertEquals(listOf("nb1"), repo.rankedNotebookIds("result", setOf("nb1", "nb2")))

        // And the prior bug stays fixed: 'is' is a word-start prefix, not a mid-word substring.
        notebook("nb3", "C"); page("p3", "nb3"); line("p3", "consistent dentist")
        assertTrue(repo.rankedNotebookIds("is", setOf("nb3")).isEmpty())
    }

    @Test
    fun `pageHighlights ignores substring-only matches`() = runTest {
        notebook("nb1", "A"); page("p1", "nb1")
        line("p1", "David is here", top = 0f)  // whole word 'is'
        line("p1", "consistent", top = 20f)    // 'is' only as a substring — must not highlight
        assertEquals(1, repo.pageHighlights("p1", "is").size)
    }

    @Test
    fun `the closest content match ranks first`() = runTest {
        // nb1's line has both tokens + the whole phrase; nb2's has only one token.
        notebook("nb1", "A"); page("p1", "nb1"); line("p1", "forward kinematics chapter")
        notebook("nb2", "B"); page("p2", "nb2"); line("p2", "gear kinematics notes")

        assertEquals(listOf("nb1", "nb2"), repo.rankedNotebookIds("forward kinematics", setOf("nb1", "nb2")))
    }

    @Test
    fun `content search is scoped to the notebook set`() = runTest {
        notebook("nb1", "A"); page("p1", "nb1"); line("p1", "woodchuck")
        assertEquals(listOf("nb1"), repo.rankedNotebookIds("woodchuck", setOf("nb1")))
        assertTrue(repo.rankedNotebookIds("woodchuck", setOf("nb-other")).isEmpty())
    }

    @Test
    fun `pageHighlights returns a box per matching line with its stored bounds`() = runTest {
        notebook("nb1", "A"); page("p1", "nb1")
        line("p1", "calculator", top = 20f)
        line("p1", "unrelated", top = 40f)

        val highlights = repo.pageHighlights("p1", "calculator")
        assertEquals(1, highlights.size)
        assertEquals(20f, highlights.single().minY, 0f)
        assertEquals(30f, highlights.single().maxY, 0f)
    }

    @Test
    fun `matchingPageIds finds the pages in a notebook that contain the query`() = runTest {
        notebook("nb1", "A"); page("p1", "nb1"); page("p2", "nb1")
        line("p1", "nothing here"); line("p2", "here is calculator")

        assertEquals(setOf("p2"), repo.matchingPageIds("nb1", "calculator"))
    }
}
