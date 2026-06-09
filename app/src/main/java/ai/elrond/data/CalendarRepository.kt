package ai.elrond.data

import ai.elrond.calendar.CalendarEvent
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stores calendar events locally — AI suggestions (pending confirmation) and
 * user-confirmed events linked back to their source note. Writing to an actual
 * device/Google/Outlook calendar is the [ai.elrond.calendar.CalendarProvider]'s
 * job and only happens after explicit confirmation.
 */
class CalendarRepository(
    private val dao: CalendarEventDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    fun observeAll(): Flow<List<CalendarEvent>> =
        dao.observeAll().map { list -> list.map(CalendarEventEntity::toDomain) }

    /** AI-suggested events awaiting confirmation. */
    fun observeSuggested(): Flow<List<CalendarEvent>> =
        dao.observeSuggested().map { list -> list.map(CalendarEventEntity::toDomain) }

    suspend fun eventsInRange(startMillis: Long, endMillis: Long): List<CalendarEvent> =
        dao.inRange(startMillis, endMillis).map(CalendarEventEntity::toDomain)

    /** Normalized titles of existing AI suggestions — to skip re-suggesting the same event. */
    suspend fun suggestedTitles(): Set<String> =
        dao.suggestedTitles().map { it.trim().lowercase() }.toSet()

    /** Persists an AI suggestion (never written to a real calendar until confirmed). */
    suspend fun addSuggestion(event: CalendarEvent, sourcePageId: String?): String {
        val id = newId()
        dao.insert(
            CalendarEventEntity(
                id = id,
                title = event.title,
                description = event.description,
                startTime = event.startTime,
                endTime = event.endTime,
                location = event.location,
                attendees = event.attendees,
                calendarId = event.calendarId,
                sourcePageId = sourcePageId,
                isAiSuggested = true,
                isConfirmed = false,
                createdAt = clock(),
            ),
        )
        return id
    }

    /** Marks a suggestion confirmed and records the backing-calendar id (if written). */
    suspend fun confirm(localId: String, externalEventId: String?) {
        val existing = dao.getById(localId) ?: return
        dao.update(existing.copy(isConfirmed = true, externalEventId = externalEventId))
    }

    suspend fun delete(localId: String) = dao.deleteById(localId)
}
