package ai.elrond.ui

import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import ai.elrond.data.TodoRepository
import ai.elrond.notes.NoteListViewModel
import ai.elrond.settings.SettingsRepository
import ai.elrond.settings.SettingsViewModel
import ai.elrond.todo.TodoViewModel
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the note browser: the empty state, creating a note via the FAB
 * (which reports the new page id for navigation), and long-press → confirm delete.
 * The note list is backed by a real in-memory Room database.
 */
@RunWith(AndroidJUnit4::class)
class NoteListScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: ElrondDatabase
    private lateinit var repository: NoteRepository
    private lateinit var viewModel: NoteListViewModel
    private lateinit var todoViewModel: TodoViewModel
    private lateinit var settingsViewModel: SettingsViewModel

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NoteRepository(
            notebookDao = db.notebookDao(),
            pageDao = db.notePageDao(),
            strokeDao = db.strokeDao(),
            aiNoteDao = db.aiNoteDao(),
            editEventDao = db.pageEditEventDao(),
        )
        viewModel = NoteListViewModel(repository)
        todoViewModel = TodoViewModel(TodoRepository(db.todoDao()))
        settingsViewModel = SettingsViewModel(SettingsRepository(ctx))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun empty_state_then_fab_create_reports_new_page() {
        var openedId: String? = null
        composeRule.setContent {
            NoteListScreen(
                onOpenNote = { openedId = it },
                onOpenSettings = {},
                viewModel = viewModel,
                todoViewModel = todoViewModel,
                settingsViewModel = settingsViewModel,
            )
        }

        composeRule.onNodeWithText("No notes yet").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("New note").performClick()
        composeRule.waitUntil(TIMEOUT) { openedId != null }
    }

    @Test
    fun long_press_then_confirm_deletes_a_note() {
        runBlocking {
            val notebook = repository.ensureDefaultNotebook()
            repository.createPage(notebook.id, customTitle = "Test Note")
        }
        composeRule.setContent {
            NoteListScreen(
                onOpenNote = {},
                onOpenSettings = {},
                viewModel = viewModel,
                todoViewModel = todoViewModel,
                settingsViewModel = settingsViewModel,
            )
        }

        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithText("Test Note").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Test Note").performTouchInput { longClick() }
        composeRule.onNodeWithText("Delete this note?").assertIsDisplayed()
        composeRule.onNodeWithText("Delete").performClick()
        composeRule.waitUntil(TIMEOUT) {
            composeRule.onAllNodesWithText("Test Note").fetchSemanticsNodes().isEmpty()
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
    }
}
