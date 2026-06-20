package ai.elrond.ui

import ai.elrond.calendar.CalendarEvent
import ai.elrond.calendar.CalendarProviderType
import ai.elrond.calendar.DateRange
import ai.elrond.calendar.NoOpOutlookAuthProvider
import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the calendar view: switching between Month / Week / Events modes, the Events
 * tab list/empty state, and the FA-11 Outlook sign-in prompt. Backed by a real in-memory Room db.
 */
@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: ElrondDatabase
    private lateinit var viewModel: CalendarViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        viewModel = CalendarViewModel(
            NoteRepository(
                notebookDao = db.notebookDao(),
                pageDao = db.notePageDao(),
                strokeDao = db.strokeDao(),
                aiNoteDao = db.aiNoteDao(),
                editEventDao = db.pageEditEventDao(),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    /** EventsViewModel built from test seams (no Hilt / MSAL / network). */
    private fun events(
        type: CalendarProviderType,
        events: List<CalendarEvent> = emptyList(),
    ) = EventsViewModel(
        providerTypeFlow = flowOf(type),
        outlookAuth = NoOpOutlookAuthProvider(),
        loadEvents = { _: CalendarProviderType, _: DateRange -> Result.success(events) },
        now = { 0L },
    )

    @Test
    fun toggles_between_month_week_and_events_modes() {
        composeRule.setContent {
            CalendarScreen(
                viewModel = viewModel,
                eventsViewModel = events(CalendarProviderType.DEVICE),
                onOpenNote = {},
            )
        }

        composeRule.onNodeWithText("Month").assertIsDisplayed()
        composeRule.onNodeWithText("Week").assertIsDisplayed()
        composeRule.onNodeWithText("Events").assertIsDisplayed()

        // Events tab with the device calendar and no events → empty-state copy.
        composeRule.onNodeWithText("Events").performClick()
        composeRule.onNodeWithText("No upcoming events.").assertIsDisplayed()

        // Week mode shows the created/edited legend (dots + labels, no "both" entry any more).
        composeRule.onNodeWithText("Week").performClick()
        composeRule.onNodeWithText("created").assertIsDisplayed()
        composeRule.onNodeWithText("edited").assertIsDisplayed()
    }

    @Test
    fun events_tab_shows_microsoft_sign_in_when_outlook_not_connected() {
        composeRule.setContent {
            CalendarScreen(
                viewModel = viewModel,
                // Outlook selected + NoOp auth (NotConfigured) → the sign-in prompt.
                eventsViewModel = events(CalendarProviderType.OUTLOOK),
                onOpenNote = {},
            )
        }

        composeRule.onNodeWithText("Events").performClick()
        composeRule.onNodeWithText("Sign in with Microsoft").assertIsDisplayed()
    }
}
