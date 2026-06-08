package ai.elrond.aibackend

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [CalendarEventExtractor] backed by any [AIProvider]. Asks for a strict JSON array
 * of events and parses it defensively (mirrors [AiTaskExtractor]).
 */
class AiCalendarEventExtractor(
    private val provider: AIProvider,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : CalendarEventExtractor {

    override suspend fun extract(noteContent: String, referenceDate: String?): Result<List<ExtractedEvent>> {
        if (noteContent.isBlank()) return Result.success(emptyList())
        val dateGuidance = referenceDate?.takeIf { it.isNotBlank() }?.let {
            "Today is $it. Resolve relative dates/times against this date and the user's timezone, " +
                "outputting absolute \"YYYY-MM-DDTHH:MM\". \"this <weekday>\" is the soonest upcoming " +
                "<weekday>; \"next <weekday>\" is the one after.\n\n"
        }.orEmpty()
        val request = AIRequest(
            input = AIInput.Text("${dateGuidance}Extract any calendar events from these notes.\n\nNOTES:\n$noteContent"),
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = MAX_TOKENS,
        )
        return provider.generate(request).mapCatching { response -> parse(response.text) }
    }

    private fun parse(responseText: String): List<ExtractedEvent> {
        val start = responseText.indexOf('[')
        val end = responseText.lastIndexOf(']')
        if (start !in 0 until end) return emptyList()
        val dtos = try {
            json.decodeFromString<List<EventDto>>(responseText.substring(start, end + 1))
        } catch (e: Exception) {
            throw AIException.Parse("Failed to parse extracted events", e)
        }
        return dtos.mapNotNull { dto ->
            val title = dto.title?.trim().orEmpty()
            if (title.isEmpty()) {
                null
            } else {
                ExtractedEvent(
                    title = title,
                    startIso = dto.start?.trim()?.ifEmpty { null },
                    endIso = dto.end?.trim()?.ifEmpty { null },
                    location = dto.location?.trim()?.ifEmpty { null },
                    attendees = dto.attendees.orEmpty().map { it.trim() }.filter { it.isNotEmpty() },
                    description = dto.description?.trim()?.ifEmpty { null },
                )
            }
        }
    }

    @Serializable
    private data class EventDto(
        val title: String? = null,
        val start: String? = null,
        val end: String? = null,
        val location: String? = null,
        val attendees: List<String>? = null,
        val description: String? = null,
    )

    companion object {
        private const val MAX_TOKENS = 1024

        private val SYSTEM_PROMPT = """
            You extract calendar events from handwritten notes. The text comes from
            handwriting recognition, so tolerate small errors.

            Return ONLY a JSON array (no prose, no markdown, no code fences). Each
            element: {"title": string, "start": "YYYY-MM-DDTHH:MM" or null,
            "end": same or null, "location": string or null,
            "attendees": [string] (names/emails), "description": string or null}.

            Only include things that are clearly scheduled events with a date and/or
            time (meetings, appointments, calls, deadlines tied to a time). Do NOT
            include vague intentions or plain tasks. If there are none, return [].
        """.trimIndent()
    }
}
