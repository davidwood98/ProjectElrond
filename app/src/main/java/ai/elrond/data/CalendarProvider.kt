package ai.elrond.data

/** Which calendar backend a [CalendarProvider] talks to. */
enum class CalendarProviderType { GOOGLE, OUTLOOK, DEVICE }

/** A calendar the user can read/write (one account may expose several). */
data class CalendarInfo(
    val id: String,
    val displayName: String,
    val accountName: String,
)

/** Half-open time window [startMillis, endMillis) in epoch millis. */
data class DateRange(val startMillis: Long, val endMillis: Long)

/**
 * A calendar event. [sourceNoteId] links it back to the note it was created from
 * (null for events that didn't originate in a note); [isAiSuggested] is true while
 * it's an unconfirmed AI suggestion. [id] is null until the event is persisted to
 * a backing calendar.
 */
data class CalendarEvent(
    val id: String? = null,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val location: String? = null,
    val attendees: List<String> = emptyList(),
    val calendarId: String? = null,
    val sourceNoteId: String? = null,
    val isAiSuggested: Boolean = false,
)

/** Raised by provider stubs that have no authenticated session yet. */
class CalendarNotAuthenticatedException(providerType: CalendarProviderType) :
    Exception("$providerType calendar is not authenticated yet")

/**
 * Swappable calendar integration. The same interface backs the device calendar
 * (fully functional) and the Google/Outlook OAuth providers (stubbed until the
 * OAuth flows are wired — see CLAUDE.md). All methods are suspend + Result so the
 * caller handles auth/permission failures without exceptions.
 *
 * Kept deliberately small and provider-agnostic so it can be reused by a future
 * iOS port (the equivalent EventKit/Graph providers implement the same contract).
 */
interface CalendarProvider {
    val type: CalendarProviderType

    suspend fun getCalendars(): Result<List<CalendarInfo>>

    suspend fun getEvents(range: DateRange): Result<List<CalendarEvent>>

    /** @return the id of the created event. */
    suspend fun createEvent(event: CalendarEvent): Result<String>

    suspend fun updateEvent(event: CalendarEvent): Result<Unit>

    suspend fun deleteEvent(id: String): Result<Unit>
}
