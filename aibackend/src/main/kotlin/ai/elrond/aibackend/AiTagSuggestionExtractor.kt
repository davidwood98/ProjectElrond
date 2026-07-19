package ai.elrond.aibackend

import kotlinx.serialization.json.Json

/**
 * [TagSuggestionExtractor] backed by any [AIProvider]. Asks the model for a strict JSON array of
 * tag-name strings and parses it defensively (the response may include code fences or prose despite
 * the instructions). The model may BOTH reuse an existing tag (semantic best-fit, where the
 * deterministic Level 1 signals miss it) AND propose new ones — the app layer classifies each return
 * as an existing-tag endorsement or a brand-new tag (see TagSuggestionProvider). Only exact
 * duplicates within a single response are collapsed here; near-duplicate/existing handling is the
 * app layer's job.
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
            parse(response.text, maxSuggestions)
        }
    }

    private fun parse(responseText: String, maxSuggestions: Int): List<String> {
        val arrayText = extractJsonArray(responseText) ?: return emptyList()
        val names = try {
            json.decodeFromString<List<String>>(arrayText)
        } catch (e: Exception) {
            throw AIException.Parse("Failed to parse suggested tags", e)
        }
        // Keep existing-tag names (they're valid endorsements now); collapse only exact repeats.
        val seen = mutableSetOf<String>()
        return names.mapNotNull { it.trim().ifEmpty { null } }
            .filter { seen.add(it.lowercase()) }
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
            "EXISTING tags you may REUSE if one is a strong fit (prefer reusing over inventing a " +
                "near-duplicate): " + existingTags.joinToString(", ") + "\n\n"
        }
        // The user's max-suggestions setting is also a BREADTH dial: 1 = very selective (anti-noise),
        // high = capture the notebook's several distinct themes. Scale the ask accordingly (FA-24d).
        val breadth = when {
            maxSuggestions <= 1 ->
                "Return ONLY the single strongest, most defining tag — be extremely selective. If no " +
                    "one theme clearly defines the whole notebook, return an empty array []."
            maxSuggestions >= 4 ->
                "Return up to $maxSuggestions tags, aiming to cover the notebook's main DISTINCT " +
                    "themes broadly (several, not just one) — but only genuinely fitting ones."
            else -> "Return up to $maxSuggestions of the most fitting tags."
        }
        return "$vocabulary$breadth Reuse the existing tags above where they fit, and/or add new " +
            "ones.\n\nNOTEBOOK CONTENT:\n$noteContent"
    }

    companion object {
        private const val MAX_TOKENS = 256

        private val SYSTEM_PROMPT = """
            You suggest short topical tags for a handwritten notebook, for a note-organising app.
            The text comes from handwriting recognition, so tolerate small errors.

            Return ONLY a JSON array of strings (no prose, no markdown, no code fences), e.g.
            ["physics", "revision", "term 2"].
            - Each tag names a BROAD subject/theme the WHOLE notebook is about — the kind of label you
              would file it under. Think "what shelf does this belong on", not "what is on this line".
            - Do NOT tag a minor detail, aside, or one-off phrase even if it appears verbatim — if a
              topic only shows up in a sentence or two, it is NOT a tag for the notebook.
            - NEVER a task, a date, a person's stray name, an app/UI feature name, or a verbatim
              sentence fragment.
            - 1-2 words, lowercase unless a proper noun.
            - You are given the existing tags. If one of them is a strong fit, RETURN IT VERBATIM
              (reuse it) rather than inventing a near-duplicate — e.g. if "settings" exists, return
              "settings", not "user settings". Otherwise propose a new tag.
            - How many to return is specified per request (a breadth dial); within that, only include
              tags you are confident broadly describe the notebook. If nothing does, return [].
        """.trimIndent()
    }
}
