package ai.elrond.calendar

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.content.TextContent
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [OutlookCalendarProvider] against a mocked Microsoft Graph (ktor-client-mock) —
 * the same HTTP-mock seam :aibackend uses for Anthropic. Never hits the real Graph API. Auth is a
 * fake token lambda, so MSAL is never loaded.
 */
class OutlookCalendarProviderTest {

    private val config = OAuthConfig("client", "msauth://ai.elrond/x", listOf("Calendars.ReadWrite"), "common")
    private val okToken: suspend () -> Result<String> = { Result.success("tok") }
    private val noToken: suspend () -> Result<String> =
        { Result.failure(CalendarNotAuthenticatedException(CalendarProviderType.OUTLOOK)) }

    private fun provider(
        tokenProvider: suspend () -> Result<String> = okToken,
        engine: MockEngine,
    ) = OutlookCalendarProvider(config, tokenProvider, engine, io = Dispatchers.Unconfined)

    private fun jsonEngine(status: HttpStatusCode = HttpStatusCode.OK, body: String, capture: (HttpRequestData) -> Unit = {}) =
        MockEngine { request ->
            capture(request)
            respond(body, status, headersOf("Content-Type", "application/json"))
        }

    private fun bodyJson(request: HttpRequestData) =
        Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test
    fun `getCalendars maps response and sends bearer token to me-calendars`() = runTest {
        var captured: HttpRequestData? = null
        val engine = jsonEngine(
            body = """{"value":[{"id":"cal1","name":"Work","owner":{"name":"Me","address":"me@x.com"}}]}""",
        ) { captured = it }

        val result = provider(engine = engine).getCalendars().getOrThrow()

        assertEquals(1, result.size)
        assertEquals("cal1", result[0].id)
        assertEquals("Work", result[0].displayName)
        assertEquals("me@x.com", result[0].accountName)
        val request = checkNotNull(captured)
        assertTrue(request.url.encodedPath.endsWith("/me/calendars"))
        assertEquals("Bearer tok", request.headers["Authorization"])
    }

    @Test
    fun `getEvents queries calendarView with time range, prefer-UTC and the extended-property expand`() = runTest {
        var captured: HttpRequestData? = null
        val engine = jsonEngine(
            body = """
                {"value":[{
                  "id":"evt1","subject":"Standup","bodyPreview":"daily",
                  "start":{"dateTime":"2026-07-01T09:00:00.0000000","timeZone":"UTC"},
                  "end":{"dateTime":"2026-07-01T09:30:00.0000000","timeZone":"UTC"},
                  "location":{"displayName":"Room 1"},
                  "attendees":[{"emailAddress":{"address":"a@x.com"},"type":"required"}],
                  "singleValueExtendedProperties":[{"id":"${OutlookCalendarProvider.SOURCE_NOTE_PROP_ID}","value":"page-7"}]
                }]}
            """.trimIndent(),
        ) { captured = it }

        val events = provider(engine = engine).getEvents(DateRange(0L, 86_400_000L)).getOrThrow()

        assertEquals(1, events.size)
        val e = events[0]
        assertEquals("evt1", e.id)
        assertEquals("Standup", e.title)
        assertEquals("Room 1", e.location)
        assertEquals(listOf("a@x.com"), e.attendees)
        assertEquals("page-7", e.sourceNoteId)
        assertEquals(OutlookTimeMapper.fromGraph(GraphDateTime("2026-07-01T09:00:00", "UTC")), e.startTime)

        val request = checkNotNull(captured)
        assertTrue(request.url.encodedPath.endsWith("/me/calendarView"))
        // The query range comes from the DateRange (UTC), not the event times in the response.
        assertEquals(OutlookTimeMapper.toGraphUtc(0L).dateTime, request.url.parameters["startDateTime"])
        assertEquals(OutlookTimeMapper.toGraphUtc(86_400_000L).dateTime, request.url.parameters["endDateTime"])
        assertTrue(request.url.parameters["\$expand"]!!.contains("singleValueExtendedProperties"))
        assertTrue(request.headers["Prefer"]!!.contains("outlook.timezone"))
    }

    @Test
    fun `createEvent posts to me-events with mapped body and source-note extended property`() = runTest {
        var captured: HttpRequestData? = null
        val engine = jsonEngine(HttpStatusCode.Created, """{"id":"evt-new"}""") { captured = it }

        val id = provider(engine = engine).createEvent(
            CalendarEvent(title = "Lunch", startTime = 1_000_000L, endTime = 2_000_000L, sourceNoteId = "page-1"),
        ).getOrThrow()

        assertEquals("evt-new", id)
        val request = checkNotNull(captured)
        assertEquals(HttpMethod.Post, request.method)
        assertTrue(request.url.encodedPath.endsWith("/me/events"))

        val body = bodyJson(request)
        assertEquals("Lunch", body["subject"]?.jsonPrimitive?.content)
        assertEquals("UTC", body["start"]!!.jsonObject["timeZone"]?.jsonPrimitive?.content)
        val ext = body["singleValueExtendedProperties"]!!.jsonArray[0].jsonObject
        assertEquals(OutlookCalendarProvider.SOURCE_NOTE_PROP_ID, ext["id"]?.jsonPrimitive?.content)
        assertEquals("page-1", ext["value"]?.jsonPrimitive?.content)
    }

    @Test
    fun `createEvent without sourceNoteId omits extended properties`() = runTest {
        var captured: HttpRequestData? = null
        val engine = jsonEngine(HttpStatusCode.Created, """{"id":"e"}""") { captured = it }

        provider(engine = engine).createEvent(CalendarEvent(title = "x", startTime = 0L, endTime = 1L)).getOrThrow()

        assertNull(bodyJson(checkNotNull(captured))["singleValueExtendedProperties"])
    }

    @Test
    fun `createEvent targets the calendar-scoped path when a calendarId is set`() = runTest {
        var captured: HttpRequestData? = null
        val engine = jsonEngine(HttpStatusCode.Created, """{"id":"e"}""") { captured = it }

        provider(engine = engine).createEvent(
            CalendarEvent(title = "x", startTime = 0L, endTime = 1L, calendarId = "cal9"),
        ).getOrThrow()

        assertTrue(checkNotNull(captured).url.encodedPath.endsWith("/me/calendars/cal9/events"))
    }

    @Test
    fun `updateEvent patches the event by id`() = runTest {
        var captured: HttpRequestData? = null
        val engine = jsonEngine(body = """{"id":"evt1"}""") { captured = it }

        provider(engine = engine).updateEvent(
            CalendarEvent(id = "evt1", title = "x", startTime = 0L, endTime = 1L),
        ).getOrThrow()

        val request = checkNotNull(captured)
        assertEquals(HttpMethod.Patch, request.method)
        assertTrue(request.url.encodedPath.endsWith("/me/events/evt1"))
    }

    @Test
    fun `updateEvent without an id fails before any network call`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond("", HttpStatusCode.OK) }

        val result = provider(engine = engine).updateEvent(CalendarEvent(title = "x", startTime = 0L, endTime = 1L))

        assertTrue(result.isFailure)
        assertFalse(called)
    }

    @Test
    fun `deleteEvent deletes the event by id`() = runTest {
        var captured: HttpRequestData? = null
        val engine = MockEngine { request -> captured = request; respond("", HttpStatusCode.NoContent) }

        provider(engine = engine).deleteEvent("evt1").getOrThrow()

        val request = checkNotNull(captured)
        assertEquals(HttpMethod.Delete, request.method)
        assertTrue(request.url.encodedPath.endsWith("/me/events/evt1"))
    }

    @Test
    fun `a missing token fails with CalendarNotAuthenticated and never calls the network`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond("", HttpStatusCode.OK) }

        val result = provider(tokenProvider = noToken, engine = engine).getCalendars()

        assertTrue(result.exceptionOrNull() is CalendarNotAuthenticatedException)
        assertFalse(called)
    }

    @Test
    fun `a Graph error maps to OutlookGraphException with the status and message`() = runTest {
        val engine = jsonEngine(
            HttpStatusCode.Unauthorized,
            """{"error":{"code":"InvalidAuthenticationToken","message":"Access token is empty."}}""",
        )

        val error = provider(engine = engine).getEvents(DateRange(0L, 1L)).exceptionOrNull() as OutlookGraphException

        assertEquals(401, error.statusCode)
        assertTrue(error.message!!.contains("Access token is empty"))
    }

    @Test
    fun `OutlookTimeMapper round-trips epoch millis through UTC`() {
        val millis = 1_751_360_400_000L // 2025-07-01T09:00:00Z, whole seconds
        val graph = OutlookTimeMapper.toGraphUtc(millis)
        assertEquals("UTC", graph.timeZone)
        // Pin the write-path string to a literal (independent of fromGraph) so a regression that
        // formatted in the device/local zone instead of UTC is caught even on a UTC CI runner.
        assertEquals("2025-07-01T09:00:00", graph.dateTime)
        assertEquals(millis, OutlookTimeMapper.fromGraph(graph))
        // Graph's 7-fraction-digit, offset-less form parses as UTC.
        assertEquals(millis, OutlookTimeMapper.fromGraph(GraphDateTime(graph.dateTime + ".0000000", "UTC")))
    }

    @Test
    fun `a non-auth token failure is propagated, not flattened to not-authenticated`() = runTest {
        var called = false
        val engine = MockEngine { called = true; respond("", HttpStatusCode.OK) }
        val failing: suspend () -> Result<String> = { Result.failure(RuntimeException("token refresh failed")) }

        val error = provider(tokenProvider = failing, engine = engine).getCalendars().exceptionOrNull()

        assertTrue(error is RuntimeException)
        assertFalse(error is CalendarNotAuthenticatedException)
        assertTrue(error!!.message!!.contains("token refresh failed"))
        assertFalse(called)
    }
}
