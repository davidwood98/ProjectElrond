package ai.elrond.aibackend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [TaskExtractor] backed by any [AIProvider]. Asks the model for a strict JSON
 * array of tasks and parses it defensively (the response text may include code
 * fences or surrounding prose despite the instructions).
 */
class AiTaskExtractor(
    private val provider: AIProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : TaskExtractor {

    override suspend fun extract(noteContent: String, referenceDate: String?): Result<List<ExtractedTask>> {
        if (noteContent.isBlank()) return Result.success(emptyList())
        val request = AIRequest(
            input = AIInput.Text(buildUserPrompt(noteContent, referenceDate)),
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = MAX_TOKENS,
        )
        return provider.generate(request).mapCatching { response -> parse(response.text) }
    }

    private fun parse(responseText: String): List<ExtractedTask> {
        val arrayText = extractJsonArray(responseText) ?: return emptyList()
        val dtos = try {
            json.decodeFromString<List<TaskDto>>(arrayText)
        } catch (e: Exception) {
            throw AIException.Parse("Failed to parse extracted tasks", e)
        }
        return dtos.mapNotNull { dto ->
            val content = dto.content?.trim().orEmpty()
            if (content.isEmpty()) {
                null
            } else {
                ExtractedTask(
                    content = content,
                    priority = dto.priority.coerceIn(0, 3),
                    dueDateIso = dto.dueDate?.trim()?.ifEmpty { null },
                )
            }
        }
    }

    /** Slices out the first JSON array so wrapping prose / code fences don't break parsing. */
    private fun extractJsonArray(text: String): String? {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        return if (start in 0 until end) text.substring(start, end + 1) else null
    }

    @Serializable
    private data class TaskDto(
        val content: String? = null,
        val priority: Int = 0,
        val dueDate: String? = null,
    )

    private fun buildUserPrompt(noteContent: String, referenceDate: String?): String {
        // The current date is put in the USER prompt (not the cached system prompt) so the
        // daily-changing value never invalidates the system-prompt cache prefix.
        val dateGuidance = referenceDate?.takeIf { it.isNotBlank() }?.let {
            "Today is $it. Resolve relative dates (\"today\", \"tomorrow\", \"this Monday\", " +
                "\"next Friday\") against this date and the user's timezone, and output dueDate as " +
                "an absolute \"YYYY-MM-DD\". Convention: \"this <weekday>\" is the soonest upcoming " +
                "<weekday>; \"next <weekday>\" is the one after.\n\n"
        }.orEmpty()
        return "${dateGuidance}Extract any action items from these handwritten notes.\n\nNOTES:\n$noteContent"
    }

    companion object {
        private const val MAX_TOKENS = 1024

        private val SYSTEM_PROMPT = """
            You extract actionable tasks from handwritten meeting/personal notes for
            a TODO list. The text comes from handwriting recognition, so tolerate
            small errors.

            Return ONLY a JSON array (no prose, no markdown, no code fences). Each
            element: {"content": string, "priority": 0-3, "dueDate": "YYYY-MM-DD" or null}.
            - content: a concise, imperative task naming a CONCRETE action AND its object —
              WHAT to do and to/about WHAT ("Email Sarah the report", "Book the meeting room").
            - priority: 0 none, 1 low, 2 medium, 3 high — infer from urgency words.
            - dueDate: only if the note clearly implies a date; otherwise null.

            Only include genuine tasks, action items, or commitments — NOT questions,
            facts, or general notes. A statement of urgency or importance with NO concrete
            action is NOT a task (e.g. "this is urgent", "do the urgent task", "top priority")
            — skip it entirely. Every task must have a real subject/object; if you cannot name
            what is actually being done, do not emit it. If there are none, return [].
        """.trimIndent()
    }
}
