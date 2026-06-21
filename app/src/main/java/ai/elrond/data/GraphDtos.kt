package ai.elrond.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Microsoft Graph v1.0 JSON shapes used by [OutlookCalendarProvider]. Only the fields the app reads
 * or writes are modelled; `ignoreUnknownKeys = true` (set on the JSON in the provider) tolerates the
 * rest. Field names already match Graph's camelCase, so no @SerialName is needed except where a
 * Kotlin keyword/style differs.
 *
 * Datetime convention: the provider always reads/writes in UTC (Prefer: outlook.timezone="UTC" on
 * reads; timeZone="UTC" on writes), so [GraphDateTime.dateTime] is an offset-less ISO string parsed
 * as UTC — see GraphTimeMapper.
 */

/** `{ "value": [ ... ] }` collection envelope used by list endpoints. */
@Serializable
data class GraphListResponse<T>(val value: List<T> = emptyList())

@Serializable
data class GraphErrorEnvelope(val error: GraphError? = null)

@Serializable
data class GraphError(val code: String? = null, val message: String? = null)

// --- Calendars ---

@Serializable
data class GraphCalendar(
    val id: String,
    val name: String? = null,
    val owner: GraphEmailAddress? = null,
)

// --- Events ---

@Serializable
data class GraphDateTime(
    val dateTime: String,
    val timeZone: String? = null,
)

@Serializable
data class GraphItemBody(
    val contentType: String? = null,
    val content: String? = null,
)

@Serializable
data class GraphLocation(
    val displayName: String? = null,
)

@Serializable
data class GraphEmailAddress(
    val address: String? = null,
    val name: String? = null,
)

@Serializable
data class GraphAttendee(
    val emailAddress: GraphEmailAddress? = null,
    val type: String? = null,
)

@Serializable
data class GraphExtendedProperty(
    val id: String,
    val value: String? = null,
)

/** An event as returned by Graph (read path). */
@Serializable
data class GraphEvent(
    val id: String? = null,
    val subject: String? = null,
    val bodyPreview: String? = null,
    val body: GraphItemBody? = null,
    val start: GraphDateTime? = null,
    val end: GraphDateTime? = null,
    val location: GraphLocation? = null,
    val attendees: List<GraphAttendee> = emptyList(),
    @SerialName("singleValueExtendedProperties")
    val singleValueExtendedProperties: List<GraphExtendedProperty> = emptyList(),
)

/** Body sent to create/update an event (write path). Nulls are omitted (explicitNulls=false). */
@Serializable
data class GraphEventWrite(
    val subject: String? = null,
    val body: GraphItemBody? = null,
    val start: GraphDateTime? = null,
    val end: GraphDateTime? = null,
    val location: GraphLocation? = null,
    val attendees: List<GraphAttendee>? = null,
    val singleValueExtendedProperties: List<GraphExtendedProperty>? = null,
)
