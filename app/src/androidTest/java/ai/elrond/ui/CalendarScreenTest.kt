package ai.elrond.ui

import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import ai.elrond.notes.CalendarViewModel
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the calendar view: switching between Month / Week / Events modes
 * and the Events-tab placeholder. Backed by a real in-memory Room database.
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

    @Test
    fun toggles_between_month_week_and_events_modes() {
        composeRule.setContent {
            CalendarScreen(viewModel = viewModel, onOpenNote = {})
        }

        composeRule.onNodeWithText("Month").assertIsDisplayed()
        composeRule.onNodeWithText("Week").assertIsDisplayed()
        composeRule.onNodeWithText("Events").assertIsDisplayed()

        // Events tab → placeholder copy.
        composeRule.onNodeWithText("Events").performClick()
        composeRule.onNodeWithText("Calendar events will appear here.", substring = true)
            .assertIsDisplayed()

        // Week mode shows the created/edited legend (dots + labels, no "both" entry any more).
        composeRule.onNodeWithText("Week").performClick()
        composeRule.onNodeWithText("created").assertIsDisplayed()
        composeRule.onNodeWithText("edited").assertIsDisplayed()
    }
}
