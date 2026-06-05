package ai.elrond.calendar

/**
 * Google Calendar API v3 provider via Google Sign-In OAuth 2.0.
 *
 * STUB: the OAuth structure is in place but no network calls are made yet. Each
 * method fails with [CalendarNotAuthenticatedException] until [authenticate] wires
 * up Google Sign-In and an authorized `Calendar` service client.
 *
 * Intended wiring (see CLAUDE.md → Google Calendar OAuth setup):
 *  - GoogleSignInClient with scope CalendarScopes.CALENDAR
 *  - GoogleAccountCredential.usingOAuth2 → com.google.api.services.calendar.Calendar
 *  - getCalendars()  -> service.calendarList().list()
 *  - getEvents(range)-> service.events().list(calendarId).setTimeMin/Max()
 *  - createEvent()   -> service.events().insert(calendarId, event)
 *  - updateEvent()   -> service.events().update(calendarId, eventId, event)
 *  - deleteEvent()   -> service.events().delete(calendarId, eventId)
 */
class GoogleCalendarProvider(
    private val config: OAuthConfig,
) : CalendarProvider {

    override val type = CalendarProviderType.GOOGLE

    /** True once Google Sign-In has produced an authorized credential. */
    var isAuthenticated: Boolean = false
        private set

    /** Placeholder for the Google Sign-In + token-exchange flow. */
    suspend fun authenticate(): Result<Unit> =
        Result.failure(CalendarNotAuthenticatedException(type))

    override suspend fun getCalendars(): Result<List<CalendarInfo>> = notAuthenticated()
    override suspend fun getEvents(range: DateRange): Result<List<CalendarEvent>> = notAuthenticated()
    override suspend fun createEvent(event: CalendarEvent): Result<String> = notAuthenticated()
    override suspend fun updateEvent(event: CalendarEvent): Result<Unit> = notAuthenticated()
    override suspend fun deleteEvent(id: String): Result<Unit> = notAuthenticated()

    private fun <T> notAuthenticated(): Result<T> =
        Result.failure(CalendarNotAuthenticatedException(type))
}

/** OAuth client configuration shared by the Google/Outlook providers. */
data class OAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val scopes: List<String>,
    val tenantId: String? = null, // Outlook/Azure only
)
