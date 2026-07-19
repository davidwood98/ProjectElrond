package ai.elrond.presentation

import ai.elrond.data.NoteRepository
import ai.elrond.data.NotebookLinkRepository
import ai.elrond.data.RecognitionCacheRepository
import ai.elrond.data.SubjectRepository
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TagRepository
import ai.elrond.domain.PendingSuggestion
import ai.elrond.domain.SuggestedTag
import ai.elrond.domain.SuggestionOrigin
import ai.elrond.domain.SuggestionType
import ai.elrond.domain.Tag
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagSuggestionProviderTest {

    private val tagRepository = mockk<TagRepository>(relaxed = true)
    private val subjectRepository = mockk<SubjectRepository>(relaxed = true)
    private val notebookLinkRepository = mockk<NotebookLinkRepository>(relaxed = true)
    private val noteRepository = mockk<NoteRepository>(relaxed = true)
    private val recognitionCache = mockk<RecognitionCacheRepository>(relaxed = true)
    private val suggestionRepository = mockk<SuggestionRepository>(relaxed = true)

    private fun provider() = TagSuggestionProvider(
        tagRepository, subjectRepository, notebookLinkRepository,
        noteRepository, recognitionCache, suggestionRepository,
    )

    private fun tag(id: String, name: String = id) = Tag(id, name, 0)

    private fun aiPending(name: String, id: String = name) =
        PendingSuggestion(pageId = "", type = SuggestionType.TAG, content = name, x = 0f, y = 0f, id = id, notebookId = "nb")

    @Test
    fun `merges Level 1 existing tags with Level 2 AI, dropping an AI duplicate of an existing tag`() = runTest {
        coEvery { tagRepository.observeTags() } returns flowOf(listOf(tag("physics")))
        coEvery { tagRepository.observeNotebookTags() } returns flowOf(mapOf("nb2" to listOf(tag("physics"))))
        coEvery { subjectRepository.observeNoteSubjects() } returns flowOf(mapOf("nb" to "s", "nb2" to "s"))
        coEvery { suggestionRepository.observeTagSuggestions("nb") } returns
            flowOf(listOf(aiPending("biology"), aiPending("physics"))) // physics dup of existing tag
        coEvery { noteRepository.pageIdsForNotebook("nb") } returns emptyList()

        val result = provider().observe("nb").first()

        // AI first (so Level 1 can't starve it), then Level 1; the AI "physics" duplicate is dropped.
        assertEquals(
            listOf(SuggestionOrigin.AI to "biology", SuggestionOrigin.EXISTING to "physics"),
            result.map { it.origin to it.name },
        )
    }

    @Test
    fun `an AI pick of an existing tag not surfaced by Level 1 is classified as AI_EXISTING`() = runTest {
        coEvery { tagRepository.observeTags() } returns
            flowOf(listOf(tag("physics"), tag("thermo", "thermodynamics")))
        coEvery { tagRepository.observeNotebookTags() } returns flowOf(mapOf("nb2" to listOf(tag("physics"))))
        coEvery { subjectRepository.observeNoteSubjects() } returns flowOf(mapOf("nb" to "s", "nb2" to "s"))
        // AI endorses an existing tag (thermodynamics) that no Level 1 signal caught, plus a new one.
        coEvery { suggestionRepository.observeTagSuggestions("nb") } returns
            flowOf(listOf(aiPending("thermodynamics"), aiPending("entropy")))
        coEvery { noteRepository.pageIdsForNotebook("nb") } returns emptyList()

        val result = provider().observe("nb").first()

        // AI first: the endorsed-existing + new AI tags precede the Level 1 subject match.
        assertEquals(
            listOf(
                Triple(SuggestionOrigin.AI_EXISTING, "thermodynamics", "thermo"),
                Triple(SuggestionOrigin.AI, "entropy", null),
                Triple(SuggestionOrigin.EXISTING, "physics", "physics"),
            ),
            result.map { Triple(it.origin, it.name, it.tag?.id) },
        )
    }

    @Test
    fun `an AI exact-or-plural match of an existing tag collapses onto it`() = runTest {
        coEvery { tagRepository.observeTags() } returns flowOf(listOf(tag("revision")))
        coEvery { tagRepository.observeNotebookTags() } returns flowOf(emptyMap())
        coEvery { subjectRepository.observeNoteSubjects() } returns flowOf(emptyMap())
        coEvery { suggestionRepository.observeTagSuggestions("nb") } returns flowOf(listOf(aiPending("revisions")))
        coEvery { noteRepository.pageIdsForNotebook("nb") } returns emptyList()

        val result = provider().observe("nb").first()

        assertEquals(SuggestionOrigin.AI_EXISTING, result.single().origin)
        assertEquals("revision", result.single().name)
    }

    @Test
    fun `a more-specific AI tag is NOT swallowed by a generic existing tag`() = runTest {
        // "graph" exists (on another notebook), AI proposes the new concept "spider graph".
        coEvery { tagRepository.observeTags() } returns flowOf(listOf(tag("graph")))
        coEvery { tagRepository.observeNotebookTags() } returns flowOf(mapOf("other" to listOf(tag("graph"))))
        coEvery { subjectRepository.observeNoteSubjects() } returns flowOf(emptyMap())
        coEvery { suggestionRepository.observeTagSuggestions("nb") } returns flowOf(listOf(aiPending("spider graph")))
        coEvery { noteRepository.pageIdsForNotebook("nb") } returns emptyList()

        val result = provider().observe("nb").first()

        assertEquals(SuggestionOrigin.AI, result.single().origin) // new tag, not AI_EXISTING
        assertEquals("spider graph", result.single().name)
    }

    @Test
    fun `accepting an AI-endorsed existing tag assigns it and marks handled, without creating`() = runTest {
        provider().accept(
            "nb",
            SuggestedTag(SuggestionOrigin.AI_EXISTING, "physics", tag = tag("physics"), suggestionId = "sug9"),
        )
        coVerify(exactly = 1) { tagRepository.assignTag("nb", "physics") }
        coVerify(exactly = 0) { tagRepository.createTag(any()) }
        coVerify(exactly = 1) { suggestionRepository.markHandled("sug9") }
    }

    @Test
    fun `accepting an existing tag assigns it without creating or marking handled`() = runTest {
        provider().accept("nb", SuggestedTag(SuggestionOrigin.EXISTING, "physics", tag = tag("physics")))
        coVerify(exactly = 1) { tagRepository.assignTag("nb", "physics") }
        coVerify(exactly = 0) { tagRepository.createTag(any()) }
        coVerify(exactly = 0) { suggestionRepository.markHandled(any()) }
    }

    @Test
    fun `accepting an AI suggestion creates, assigns, and marks it handled`() = runTest {
        coEvery { tagRepository.createTag("biology") } returns tag("bio-id", "biology")
        coEvery { tagRepository.assignTag(any(), any()) } just Runs
        coEvery { suggestionRepository.markHandled("sug1") } just Runs

        provider().accept("nb", SuggestedTag(SuggestionOrigin.AI, "biology", suggestionId = "sug1"))

        coVerify(exactly = 1) { tagRepository.createTag("biology") }
        coVerify(exactly = 1) { tagRepository.assignTag("nb", "bio-id") }
        coVerify(exactly = 1) { suggestionRepository.markHandled("sug1") }
    }
}
