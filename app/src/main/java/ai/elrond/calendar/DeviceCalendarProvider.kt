package ai.elrond.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.util.TimeZone
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fully-functional [CalendarProvider] over the device's calendars via
 * [CalendarContract]. Requires the READ_CALENDAR / WRITE_CALENDAR runtime
 * permissions; without them the ContentResolver throws SecurityException, which
 * is surfaced as a failed [Result].
 *
 * The [ContentResolver] is injected so this can be unit-tested with a mock.
 * Attendee writes are intentionally out of scope for the POC (noted in CLAUDE.md).
 */
class DeviceCalendarProvider(
    private val contentResolver: ContentResolver,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : CalendarProvider {

    override val type = CalendarProviderType.DEVICE

    override suspend fun getCalendars(): Result<List<CalendarInfo>> = io {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
        )
        contentResolver.query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
            .use { cursor ->
                buildList {
                    while (cursor != null && cursor.moveToNext()) {
                        add(
                            CalendarInfo(
                                id = cursor.getLong(0).toString(),
                                displayName = cursor.getString(1) ?: "",
                                accountName = cursor.getString(2) ?: "",
                            ),
                        )
                    }
                }
            }
    }

    override suspend fun getEvents(range: DateRange): Result<List<CalendarEvent>> = io {
        val projection = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.CALENDAR_ID,
        )
        val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
        val args = arrayOf(range.startMillis.toString(), range.endMillis.toString())
        contentResolver.query(CalendarContract.Events.CONTENT_URI, projection, selection, args, null)
            .use { cursor ->
                buildList {
                    while (cursor != null && cursor.moveToNext()) {
                        add(
                            CalendarEvent(
                                id = cursor.getLong(0).toString(),
                                title = cursor.getString(1) ?: "",
                                description = cursor.getString(2),
                                startTime = cursor.getLong(3),
                                endTime = cursor.getLong(4),
                                location = cursor.getString(5),
                                calendarId = cursor.getLong(6).toString(),
                            ),
                        )
                    }
                }
            }
    }

    override suspend fun createEvent(event: CalendarEvent): Result<String> = io {
        val values = event.toContentValues()
        val uri = contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: error("Calendar insert returned no URI")
        ContentUris.parseId(uri).toString()
    }

    override suspend fun updateEvent(event: CalendarEvent): Result<Unit> = io {
        val id = requireNotNull(event.id) { "updateEvent requires a non-null id" }
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong())
        contentResolver.update(uri, event.toContentValues(), null, null)
        Unit
    }

    override suspend fun deleteEvent(id: String): Result<Unit> = io {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id.toLong())
        contentResolver.delete(uri, null, null)
        Unit
    }

    private fun CalendarEvent.toContentValues(): ContentValues = ContentValues().apply {
        calendarId?.let { put(CalendarContract.Events.CALENDAR_ID, it.toLong()) }
        put(CalendarContract.Events.TITLE, title)
        description?.let { put(CalendarContract.Events.DESCRIPTION, it) }
        put(CalendarContract.Events.DTSTART, startTime)
        put(CalendarContract.Events.DTEND, endTime)
        location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
    }

    /** Runs [block] on IO and wraps the result/exception (e.g. missing permission) in a [Result]. */
    private suspend fun <T> io(block: () -> T): Result<T> =
        withContext(io) { runCatching(block) }
}
