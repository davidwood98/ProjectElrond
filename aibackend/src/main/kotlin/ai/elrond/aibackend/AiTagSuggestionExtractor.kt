package ai.elrond.aibackend

import kotlinx.serialization.json.Json

/**
 * [TagSuggestionExtractor] backed by any [AIProvider]. Asks the model for a strict JSON array of
 * tag-name strings and parses it defensively (the response may include code fences or prose despite
 * the instructions). Names duplicating an existing tag (case-insensitive) are dropped here too, so a
 * lax model can't smuggle a Level-1 candidate into Level 2.
 */
class AiTagSuggestionExtractor(
    private val provider: AIProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : TagSuggestionExtractor {

    override suspend fun extract(
        noteContent: String,
        existingTags: List<String>,
        maxSuggestions: Int,
    ): Result<List<String>> {
        if (noteContent.isBlank() || maxSuggestions <= 0) return Result.success(emptyList())
        val request = AIRequest(
            input = AIInput.Text(buildUserPrompt(noteContent, existingTags, maxSuggestions)),
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = MAX_TOKENS,
        )
        return provider.generate(request).mapCatching { response ->
            parse(response.text, existingTags, maxSuggestions)
        }
    }

    private fun parse(responseText: String, existingTags: List<String>, maxSuggestions: Int): List<String> {
        val arrayText = extractJsonArray(responseText) ?: return emptyList()
        val names = try {
            json.decodeFromString<List<String>>(arrayText)
        } catch (e: Exception) {
            throw AIException.Parse("Failed to parse suggested tags", e)
        }
        val existingLower = existingTags.map { it.trim().lowercase() }.toSet()
        val seen = mutableSetOf<String>()
        return names.mapNotNull { it.trim().ifEmpty { null } }
            .filter { it.lowercase() !in existingLower && seen.add(it.lowercase()) }
            .take(maxSuggestions)
    }

    /** Slices out the first JSON array so wrapping prose / code fences don't break parsing. */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    private fun buildUserPrompt(noteContent: String, existingTags: List<String>, maxSuggestions: Int): String {
        val vocabulary = if (existingTags.isEmpty()) {
            "There are no existing tags yet.\n\n"
        } else {
            "Tags that ALREADY EXIST (do NOT repeat any of these): " +
                existingTags.joinToString(", ") + "\n\n"
        }
        return "${vocabulary}Suggest up to $maxSuggestions NEW topical tags for this notebook.\n\n" +
            "NOTEBOOK CONTENT:\n$noteContent"
    }

    companion object {
        private const val MAX_TOKENS = 256

        private val SYSTEM_PROMPT = """
            You suggest short topical tags for a handwritten notebook, for a note-organising app.
            The text comes from handwriting recognition, so tolerate small errors.

            Return ONLY a JSON array of strings (no prose, no markdown, no code fences), e.g.
            ["physics", "revision", "term 2"].
            - Each tag: 1-2 words, a topic/subject/category the whole notebook is about — NOT a task,
              a date, a person's stray name, or a verbatim sentence.
            - Lowercase unless it is a proper noun.
            - Never repeat a tag that already exists (you are given that list).
            - Only suggest tags you are confident genuinely describe the notebook. If none fit,
              return [].
        """.trimIndent()
    }
}
