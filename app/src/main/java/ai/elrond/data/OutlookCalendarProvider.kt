package ai.elrond.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Microsoft Outlook calendar via the Microsoft Graph v1.0 REST API, authenticated through MSAL.
 *
 * Architecture (mirrors AnthropicProvider): raw Graph REST over Ktor + kotlinx-serialization rather
 * than the heavy official Graph SDK, so the whole provider is unit-testable with `ktor-client-mock`
 * (the same HTTP-mock seam :aibackend uses) and the module stays portable. ALL Graph calls live in
 * this class — nothing Graph-specific leaks into ViewModels (the same rule as the Anthropic API).
 *
 * Auth is injected as a [tokenProvider] (typically [OutlookAuthProvider.currentToken]) so this class
 * doesn't depend on MSAL: every call fetches a silently-refreshed bearer token first and, when none
 * is available, fails with [CalendarNotAuthenticatedException] (the UI then offers interactive
 * sign-in). The [engine] is injectable for tests; production uses CIO.
 *
 * Calendar writes still require explicit user confirmation upstream (CalendarViewModel) — this class
 * is the transport, not the policy.
 */
class OutlookCalendarProvider(
    private val config: OAuthConfig,
    private val tokenProvider: suspend () -> Result<String>,
    engine: HttpClientEngine = CIO.create(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val baseUrl: String = GRAPH_BASE_URL,
) : CalendarProvider {

    override val type = CalendarProviderType.OUTLOOK

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    private val client = HttpClient(engine) {
        install(ContentNegotiation) { json(this@OutlookCalendarProvider.json) }
        install(HttpRequestRetry) {
            maxRetries = 2
            retryIf { _, response -> response.status.value == 429 || response.status.value >= 500 }
            exponentialDelay()
        }
        expectSuccess = false
    }

    override suspend fun getCalendars(): Result<List<CalendarInfo>> = call {
        val token = tokenProvider().getOrElse { return@call Result.failure(it) }
        val response = client.get("$baseUrl/me/calendars") { bearer(token) }
        decode<GraphListResponse<GraphCalendar>>(response).map { list ->
            list.value.map {
                CalendarInfo(
                    id = it.id,
                    displayName = it.name ?: "",
                    accountName = it.owner?.address ?: it.owner?.name ?: "",
                )
            }
        }
    }

    override suspend fun getEvents(range: DateRange): Result<List<CalendarEvent>> = call {
        val token = tokenProvider().getOrElse { return@call Result.failure(it) }
        val response = client.get("$baseUrl/me/calendarView") {
            bearer(token)
            // Read times back in UTC so the offset-less dateTime parses unambiguously.
            header("Prefer", "outlook.timezone=\"UTC\"")
            parameter("startDateTime", OutlookTimeMapper.toGraphUtc(range.startMillis).dateTime)
            parameter("endDateTime", OutlookTimeMapper.toGraphUtc(range.endMillis).dateTime)
            parameter("\$orderby", "start/dateTime")
            parameter("\$top", "100")
            parameter(
                "\$expand",
                "singleValueExtendedProperties(\$filter=id eq '$SOURCE_NOTE_PROP_ID')",
            )
        }
        decode<GraphListResponse<GraphEvent>>(response).map { list ->
            list.value.map { it.toDomain() }
        }
    }

    override suspend fun createEvent(event: CalendarEvent): Result<String> = call {
        val token = tokenProvider().getOrElse { return@call Result.failure(it) }
        val path = event.calendarId?.let { "$baseUrl/me/calendars/$it/events" } ?: "$baseUrl/me/events"
        val response = client.post(path) {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(event.toGraphWrite())
        }
        decode<GraphEvent>(response).mapCatching { it.id ?: error("Graph created event without an id") }
    }

    override suspend fun updateEvent(event: CalendarEvent): Result<Unit> = call {
        val id = event.id ?: return@call Result.failure(IllegalArgumentException("updateEvent requires a non-null id"))
        val token = tokenProvider().getOrElse { return@call Result.failure(it) }
        val response = client.patch("$baseUrl/me/events/$id") {
            bearer(token)
            contentType(ContentType.Application.Json)
            setBody(event.toGraphWrite())
        }
        decode<GraphEvent>(response).map { }
    }

    override suspend fun deleteEvent(id: String): Result<Unit> = call {
        val token = tokenProvider().getOrElse { return@call Result.failure(it) }
        val response = client.delete("$baseUrl/me/events/$id") { bearer(token) }
        if (response.status.isSuccess()) Result.success(Unit) else Result.failure(errorFrom(response))
    }

    fun close() = client.close()

    // --- helpers ---

    /** Runs [block] on IO, turning any thrown exception into a failed [Result] (rethrows cancellation). */
    private suspend fun <T> call(block: suspend () -> Result<T>): Result<T> = withContext(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend inline fun <reified T> decode(response: HttpResponse): Result<T> {
        val text = response.bodyAsText()
        return if (response.status.isSuccess()) {
            runCatching { json.decodeFromString<T>(text) }
        } else {
            Result.failure(errorFromBody(response.status.value, text))
        }
    }

    private suspend fun errorFrom(response: HttpResponse): Throwable =
        errorFromBody(response.status.value, response.bodyAsText())

    private fun errorFromBody(status: Int, body: String): Throwable {
        val message = runCatching {
            json.decodeFromString<GraphErrorEnvelope>(body).error?.message
        }.getOrNull() ?: "Microsoft Graph error (HTTP $status)"
        return OutlookGraphException(status, message)
    }

    private fun GraphEvent.toDomain(): CalendarEvent = CalendarEvent(
        id = id,
        title = subject ?: "",
        description = bodyPreview ?: body?.content,
        startTime = start?.let { OutlookTimeMapper.fromGraph(it) } ?: 0L,
        endTime = end?.let { OutlookTimeMapper.fromGraph(it) } ?: 0L,
        location = location?.displayName,
        attendees = attendees.mapNotNull { it.emailAddress?.address },
        calendarId = null,
        sourceNoteId = singleValueExtendedProperties
            .firstOrNull { it.id.equals(SOURCE_NOTE_PROP_ID, ignoreCase = true) }
            ?.value,
    )

    private fun CalendarEvent.toGraphWrite(): GraphEventWrite = GraphEventWrite(
        subject = title,
        body = description?.let { GraphItemBody(contentType = "Text", content = it) },
        start = OutlookTimeMapper.toGraphUtc(startTime),
        end = OutlookTimeMapper.toGraphUtc(endTime),
        location = location?.let { GraphLocation(displayName = it) },
        attendees = attendees.takeIf { it.isNotEmpty() }?.map {
            GraphAttendee(emailAddress = GraphEmailAddress(address = it), type = "required")
        },
        singleValueExtendedProperties = sourceNoteId?.let {
            listOf(GraphExtendedProperty(id = SOURCE_NOTE_PROP_ID, value = it))
        },
    )

    companion object {
        const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"

        /**
         * Stable id for the custom extended property that links a Graph event back to its source
         * note. Format per Graph docs: `String {GUID} Name <name>`. The GUID is an app-private
         * namespace; the name is human-readable. Used both on write and in the read `$expand` filter.
         */
        const val SOURCE_NOTE_PROP_ID =
            "String {f1e2d3c4-5b6a-4789-90ab-cdef01234567} Name ElrondSourceNoteId"
    }
}

/** Adds the bearer token header to a Graph request. */
private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

/** A failed Microsoft Graph response (non-2xx), carrying the HTTP status and Graph error message. */
class OutlookGraphException(val statusCode: Int, message: String) :
    Exception("$message [HTTP $statusCode]")

/**
 * Converts between epoch-millis [CalendarEvent] times and Graph's `{ dateTime, timeZone }` shape.
 * Pure + JVM-testable. The app standardises on UTC: writes send timeZone="UTC"; reads use the
 * `Prefer: outlook.timezone="UTC"` header so the offset-less `dateTime` is interpreted as UTC.
 */
object OutlookTimeMapper {

    private val WRITE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    // Graph returns 7 fractional digits (e.g. 2019-03-15T12:00:00.0000000); accept 0–9 optionally.
    private val READ_FORMAT: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .toFormatter()

    fun toGraphUtc(epochMillis: Long): GraphDateTime {
        val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC).toLocalDateTime()
        return GraphDateTime(dateTime = local.format(WRITE_FORMAT), timeZone = "UTC")
    }

    fun fromGraph(dt: GraphDateTime): Long {
        val raw = dt.dateTime.trim()
        // Honour an explicit offset/Z if Graph ever includes one (it doesn't with our Prefer header).
        runCatching {
            return Instant.from(DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(raw)).toEpochMilli()
        }
        return LocalDateTime.parse(raw, READ_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
    }
}
