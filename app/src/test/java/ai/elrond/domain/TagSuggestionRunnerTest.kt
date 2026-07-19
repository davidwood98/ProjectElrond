package ai.elrond.domain

import ai.elrond.aibackend.TagSuggestionExtractor
import ai.elrond.data.SuggestionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagSuggestionRunnerTest {

    private fun extractorReturning(vararg names: String) = object : TagSuggestionExtractor {
        override suspend fun extract(noteContent: String, existingTags: List<String>, maxSuggestions: Int) =
            Result.success(names.toList())
    }

    private fun runner(
        text: String,
        extractor: TagSuggestionExtractor?,
        repo: SuggestionRepository,
        existingTags: List<String> = emptyList(),
        lastHash: String? = null,
        onSaveHash: (String) -> Unit = {},
    ) = TagSuggestionRunner(
        aggregateNotebookText = { text },
        tagExtractor = extractor,
        existingTagNames = { existingTags },
        suggestionRepository = repo,
        loadHash = { lastHash },
        saveHash = { _, h -> onSaveHash(h) },
    )

    @Test
    fun `writes rows for AI picks, keeping existing-tag endorsements, dropping already-suggested`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        coEvery { repo.existingTagContents("nb") } returns setOf("waves")
        val added = slot<List<PendingSuggestion>>()
        coEvery { repo.add(capture(added)) } returns Unit

        val count = runner(
            text = "physics revision",
            // "physics" matches an existing tag but is KEPT now (endorsement, classified downstream);
            // "waves" was already suggested → dropped.
            extractor = extractorReturning("physics", "waves", "revision"),
            repo = repo,
            existingTags = listOf("physics"),
        ).run("nb", "p1")

        assertEquals(2, count)
        assertEquals(listOf("physics", "revision"), added.captured.map { it.content })
        val row = added.captured.first()
        assertEquals(SuggestionType.TAG, row.type)
        assertEquals("nb", row.notebookId)
    }

    @Test
    fun `near-duplicates within one batch collapse to a single row`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        coEvery { repo.existingTagContents("nb") } returns emptySet()
        val added = slot<List<PendingSuggestion>>()
        coEvery { repo.add(capture(added)) } returns Unit

        runner(
            text = "content",
            extractor = extractorReturning("revision", "revisions", "biology"), // plural near-dup
            repo = repo,
        ).run("nb", "p1")

        assertEquals(listOf("revision", "biology"), added.captured.map { it.content })
    }

    @Test
    fun `unchanged content skips the model call entirely`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        var extracted = false
        val extractor = object : TagSuggestionExtractor {
            override suspend fun extract(noteContent: String, existingTags: List<String>, maxSuggestions: Int): Result<List<String>> {
                extracted = true
                return Result.success(listOf("x"))
            }
        }
        val hash = "physics".hashCode().toString()
        val count = runner(text = "physics", extractor = extractor, repo = repo, lastHash = hash).run("nb", "p1")

        assertEquals(0, count)
        assertTrue(!extracted)
        coVerify(exactly = 0) { repo.trimActiveTagSuggestions(any(), any()) }
        coVerify(exactly = 0) { repo.add(any()) }
    }

    @Test
    fun `changed content accumulates new suggestions and trims to the limit, never wholesale-clears`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        coEvery { repo.existingTagContents("nb") } returns emptySet()

        TagSuggestionRunner(
            aggregateNotebookText = { "new content" },
            tagExtractor = extractorReturning("biology"),
            existingTagNames = { emptyList() },
            suggestionRepository = repo,
            loadHash = { "stale-hash" },
            saveHash = { _, _ -> },
            maxSuggestions = 4,
        ).run("nb", "p1")

        coVerify(exactly = 1) { repo.add(any()) }
        coVerify(exactly = 1) { repo.trimActiveTagSuggestions("nb", 4) } // rolling window, not a wipe
    }

    @Test
    fun `blank text does nothing`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        val count = runner(text = "   ", extractor = extractorReturning("x"), repo = repo).run("nb", "p1")
        assertEquals(0, count)
        coVerify(exactly = 0) { repo.add(any()) }
    }

    @Test
    fun `null extractor does nothing`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        val count = runner(text = "content", extractor = null, repo = repo).run("nb", "p1")
        assertEquals(0, count)
    }

    @Test
    fun `hash is saved after a run so the next unchanged save skips`() = runTest {
        val repo = mockk<SuggestionRepository>(relaxed = true)
        coEvery { repo.existingTagContents(any()) } returns emptySet()
        var saved: String? = null
        runner(
            text = "content",
            extractor = extractorReturning("tag"),
            repo = repo,
            onSaveHash = { saved = it },
        ).run("nb", "p1")
        assertEquals("content".hashCode().toString(), saved)
    }
}
