package ai.elrond.ui

import ai.elrond.data.CalendarEvent
import ai.elrond.data.CalendarProviderType
import ai.elrond.data.DateRange
import ai.elrond.data.NoOpOutlookAuthProvider
import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import ai.elrond.data.ThumbnailCache
import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
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
 * Compose UI test for the FA-15 Timeline calendar: the Month/Week toggle + created/edited legend,
 * and (separately) the EventsTab Outlook sign-in prompt now that Events lives under the Calendar nav.
 * Backed by a real in-memory Room db.
 */
@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: ElrondDatabase
    private lateinit var viewModel: CalendarViewModel
    private lateinit var noteListViewModel: NoteListViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = NoteRepository(
            notebookDao = db.notebookDao(),
            pageDao = db.notePageDao(),
            strokeDao = db.strokeDao(),
            aiNoteDao = db.aiNoteDao(),
            editEventDao = db.pageEditEventDao(),
        )
        viewModel = CalendarViewModel(repository)
        noteListViewModel = NoteListViewModel(
            repository,
            ThumbnailCache(ctx.cacheDir.resolve("thumb-cal-${System.nanoTime()}")),
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
    fun toggles_between_month_and_week_modes_with_legend() {
        composeRule.setContent {
            CalendarScreen(
                viewModel = viewModel,
                eventsViewModel = events(CalendarProviderType.DEVICE),
                noteListViewModel = noteListViewModel,
                onOpenNote = {},
            )
        }

        composeRule.onNodeWithText("Month").assertIsDisplayed()
        composeRule.onNodeWithText("Week").assertIsDisplayed()

        // Created/Edited legend (dots + labels, no "both" entry).
        composeRule.onNodeWithText("Created").assertIsDisplayed()
        composeRule.onNodeWithText("Edited").assertIsDisplayed()

        // Week mode keeps the legend.
        composeRule.onNodeWithText("Week").performClick()
        composeRule.onNodeWithText("Created").assertIsDisplayed()
    }

    @Test
    fun events_tab_shows_microsoft_sign_in_when_outlook_not_connected() {
        composeRule.setContent {
            // Outlook selected + NoOp auth (NotConfigured) → the sign-in prompt.
            EventsTab(events(CalendarProviderType.OUTLOOK))
        }
        composeRule.onNodeWithText("Sign in with Microsoft").assertIsDisplayed()
    }
}
