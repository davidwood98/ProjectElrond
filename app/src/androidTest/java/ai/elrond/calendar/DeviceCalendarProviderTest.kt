package ai.elrond.calendar

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented CRUD coverage for [DeviceCalendarProvider] against the real
 * [CalendarContract] (not available to JVM/Robolectric). Creates a throwaway local
 * calendar in setup and removes it in teardown so the device's calendars are untouched.
 *
 * Requires READ/WRITE_CALENDAR — granted at runtime via [GrantPermissionRule].
 */
@RunWith(AndroidJUnit4::class)
class DeviceCalendarProviderTest {

    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(
        android.Manifest.permission.READ_CALENDAR,
        android.Manifest.permission.WRITE_CALENDAR,
    )

    private lateinit var resolver: ContentResolver
    private lateinit var provider: DeviceCalendarProvider
    private var testCalendarId: Long = -1L

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        resolver = ctx.contentResolver
        provider = DeviceCalendarProvider(resolver)
        testCalendarId = createLocalCalendar(resolver)
    }

    @After
    fun tearDown() {
        deleteLocalCalendar(resolver, testCalendarId)
    }

    @Test
    fun create_read_update_delete_event() = runBlocking {
        val start = 1_800_000_000_000L
        val end = start + 3_600_000L
        val range = DateRange(start - 1_000L, start + 10_000_000L)

        val eventId = provider.createEvent(
            CalendarEvent(
                title = "Standup",
                startTime = start,
                endTime = end,
                calendarId = testCalendarId.toString(),
            ),
        ).getOrThrow()

        assertTrue(
            provider.getEvents(range).getOrThrow().any { it.id == eventId && it.title == "Standup" },
        )

        provider.updateEvent(
            CalendarEvent(
                id = eventId,
                title = "Standup (updated)",
                startTime = start,
                endTime = end,
                calendarId = testCalendarId.toString(),
            ),
        ).getOrThrow()
        assertEquals(
            "Standup (updated)",
            provider.getEvents(range).getOrThrow().first { it.id == eventId }.title,
        )

        provider.deleteEvent(eventId).getOrThrow()
        assertFalse(provider.getEvents(range).getOrThrow().any { it.id == eventId })
    }

    @Test
    fun getCalendars_includes_the_test_calendar() = runBlocking {
        assertTrue(
            provider.getCalendars().getOrThrow().any { it.id == testCalendarId.toString() },
        )
    }

    private companion object {
        const val ACCOUNT_NAME = "elrond-test@local"

        fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()

        fun createLocalCalendar(resolver: ContentResolver): Long {
            val values = ContentValues().apply {
                put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(CalendarContract.Calendars.NAME, "Elrond Test")
                put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "Elrond Test")
                put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF2196F3.toInt())
                put(
                    CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                    CalendarContract.Calendars.CAL_ACCESS_OWNER,
                )
                put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
                put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            }
            val uri = resolver.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), values)
                ?: error("Failed to create a test calendar")
            return ContentUris.parseId(uri)
        }

        fun deleteLocalCalendar(resolver: ContentResolver, id: Long) {
            if (id <= 0L) return
            resolver.delete(
                ContentUris.withAppendedId(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), id),
                null,
                null,
            )
        }
    }
}
