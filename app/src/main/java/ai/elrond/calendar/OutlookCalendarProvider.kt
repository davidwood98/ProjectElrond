package ai.elrond.calendar

/**
 * Microsoft Outlook calendar via Microsoft Graph API, authenticated with MSAL
 * OAuth 2.0.
 *
 * STUB: OAuth structure only; methods fail with [CalendarNotAuthenticatedException]
 * until [authenticate] wires up MSAL and a Graph client.
 *
 * Intended wiring (see CLAUDE.md → Outlook / Microsoft Graph OAuth setup):
 *  - MSAL PublicClientApplication, scopes ["Calendars.ReadWrite"]
 *  - acquireToken → GraphServiceClient
 *  - getCalendars()  -> graph.me().calendars().buildRequest().get()
 *  - getEvents(range)-> graph.me().calendarView() with startDateTime/endDateTime
 *  - createEvent()   -> graph.me().events().buildRequest().post(event)
 *  - updateEvent()   -> graph.me().events(eventId).buildRequest().patch(event)
 *  - deleteEvent()   -> graph.me().events(eventId).buildRequest().delete()
 */
class OutlookCalendarProvider(
    private val config: OAuthConfig,
) : CalendarProvider {

    override val type = CalendarProviderType.OUTLOOK

    var isAuthenticated: Boolean = false
        private set

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
