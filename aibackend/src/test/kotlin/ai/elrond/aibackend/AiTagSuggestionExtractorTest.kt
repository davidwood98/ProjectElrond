package ai.elrond.aibackend

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTagSuggestionExtractorTest {

    private fun providerReturning(text: String) = object : AIProvider {
        var lastRequest: AIRequest? = null
        override suspend fun generate(request: AIRequest): Result<AIResponse> {
            lastRequest = request
            return Result.success(AIResponse(text, inputTokens = 1, outputTokens = 1, stopReason = "end_turn"))
        }
    }

    @Test
    fun `parses a clean json array of tag names`() = runTest {
        val names = AiTagSuggestionExtractor(providerReturning("""["physics","revision","term 2"]"""))
            .extract("notes about physics revision", existingTags = emptyList()).getOrThrow()
        assertEquals(listOf("physics", "revision", "term 2"), names)
    }

    @Test
    fun `tolerates code fences and surrounding prose`() = runTest {
        val names = AiTagSuggestionExtractor(
            providerReturning("Suggestions:\n```json\n[\"biology\"]\n```"),
        ).extract("cells", existingTags = emptyList()).getOrThrow()
        assertEquals(listOf("biology"), names)
    }

    @Test
    fun `keeps existing-tag names - reuse is allowed and the app layer classifies them`() = runTest {
        val names = AiTagSuggestionExtractor(providerReturning("""["Physics","chemistry"]"""))
            .extract("notes", existingTags = listOf("physics")).getOrThrow()
        assertEquals(listOf("Physics", "chemistry"), names)
    }

    @Test
    fun `de-dupes and caps to maxSuggestions`() = runTest {
        val names = AiTagSuggestionExtractor(providerReturning("""["a","a","b","c","d","e","f"]"""))
            .extract("notes", existingTags = emptyList(), maxSuggestions = 3).getOrThrow()
        assertEquals(listOf("a", "b", "c"), names)
    }

    @Test
    fun `blank content short-circuits without calling the provider`() = runTest {
        val provider = providerReturning("""["x"]""")
        val names = AiTagSuggestionExtractor(provider).extract("   ", existingTags = emptyList()).getOrThrow()
        assertTrue(names.isEmpty())
        assertEquals(null, provider.lastRequest)
    }

    @Test
    fun `existing tags are passed to the model as vocabulary`() = runTest {
        val provider = providerReturning("[]")
        AiTagSuggestionExtractor(provider).extract("notes", existingTags = listOf("maths")).getOrThrow()
        val prompt = (provider.lastRequest?.input as AIInput.Text).text
        assertTrue(prompt.contains("maths"))
    }
}
