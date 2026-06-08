package ai.elrond.aibackend

/**
 * A calendar event the AI detected in note content, before it becomes a
 * suggestion. Pure data (no Android types) for the future iOS port. Times are
 * ISO-8601 strings; the app layer parses them into timestamps.
 *
 * @param startIso e.g. "2026-06-10T15:00" (local, no zone) — null if undetermined.
 */
data class ExtractedEvent(
    val title: String,
    val startIso: String? = null,
    val endIso: String? = null,
    val location: String? = null,
    val attendees: List<String> = emptyList(),
    val description: String? = null,
)

/**
 * Finds dates, times, people, and locations in note content and proposes calendar
 * events. Independent of any trigger, like [TaskExtractor], so it can be driven by
 * `/Q` today or a background job later. Nothing is ever written to a real calendar
 * here — these are suggestions for the user to confirm.
 */
interface CalendarEventExtractor {
    /**
     * @param referenceDate the device's "today" as a human-readable string (e.g.
     *        "Friday 2026-06-05") used to anchor relative dates/times. Null disables
     *        relative-date guidance (back-compat).
     */
    suspend fun extract(noteContent: String, referenceDate: String? = null): Result<List<ExtractedEvent>>
}
