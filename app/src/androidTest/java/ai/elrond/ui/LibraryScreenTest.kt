package ai.elrond.ui

import ai.elrond.data.CalendarProviderType
import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoOpOutlookAuthProvider
import ai.elrond.data.NoteRepository
import ai.elrond.data.SettingsRepository
import ai.elrond.data.SubjectRepository
import ai.elrond.data.TagRepository
import ai.elrond.data.ThumbnailCache
import ai.elrond.data.TodoRepository
import ai.elrond.presentation.CalendarViewModel
import ai.elrond.presentation.EventsViewModel
import ai.elrond.presentation.NoteListViewModel
import ai.elrond.presentation.SettingsViewModel
import ai.elrond.presentation.SubjectViewModel
import ai.elrond.presentation.TagViewModel
import ai.elrond.presentation.TodoViewModel
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI test for the FA-14 Library home (the live note browser that replaced NoteListScreen):
 * the empty state, FAB create (reports the new page id for navigation), and long-press → confirm
 * delete in the Notes section. Backed by a real in-memory Room database; the calendar/events
 * ViewModels are constructed with no-op deps since these tests stay on the Notes tab.
 */
@RunWith(AndroidJUnit4::class)
class LibraryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var db: ElrondDatabase
    private lateinit var repository: NoteRepository
    private lateinit var thumbnailCache: ThumbnailCache
    private lateinit var noteListViewModel: NoteListViewModel
    private lateinit var todoViewModel: TodoViewModel
    private lateinit var settingsViewModel: SettingsViewModel
    private lateinit var calendarViewModel: CalendarViewModel
    private lateinit var eventsViewModel: EventsViewModel
    private lateinit var subjectViewModel: SubjectViewModel
    private lateinit var tagViewModel: TagViewModel

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
        thumbnailCache = ThumbnailCache(ctx.cacheDir.resolve("thumb-test-${System.nanoTime()}"))
        noteListViewModel = NoteListViewModel(repository, thumbnailCache)
        todoViewModel = TodoViewModel(TodoRepository(db.todoDao()))
        val settingsRepository = SettingsRepository(ctx)
        settingsViewModel = SettingsViewModel(settingsRepository)
        subjectViewModel = SubjectViewModel(
            SubjectRepository(db.subjectDao(), db.noteSubjectDao()),
            settingsRepository,
        )
        tagViewModel = TagViewModel(TagRepository(db.tagDao(), db.notebookTagDao()))
        calendarViewModel = CalendarViewModel(repository)
        eventsViewModel = EventsViewModel(
            providerTypeFlow = flowOf(CalendarProviderType.DEVICE),
            outlookAuth = NoOpOutlookAuthProvider(),
            loadEvents = { _, _ -> Result.success(emptyList()) },
        )
    }

    @After
    fun tearDown() = db.close()

    private fun setLibrary(onOpenNote: (String) -> Unit = {}) {
        composeRule.setContent {
            LibraryScreen(
                onOpenNote = onOpenNote,
                onOpenSettings = {},
                noteListViewModel = noteListViewModel,
                todoViewModel = todoViewModel,
                settingsViewModel = settingsViewModel,
                calendarViewModel = calendarViewModel,
                eventsViewModel = eventsViewModel,
                subjectViewModel = subjectViewModel,
                tagViewModel = tagViewModel,
            )
        }
    }

    @Test
    fun empty_state_then_fab_create_reports_new_page() {
        var openedId: String? = null
        setLibrary(onOpenNote = { openedId = it })

        composeRule.onNodeWithText("No notes yet").assertIsDisplayed()

        composeRule.onNodeWithContentDescription("New note").performClick()
        composeRule.waitUntil(TIMEOUT) { openedId != null }
    }

    @Test
    fun long_press_then_confirm_deletes_a_note() {
        runBlocking {
            // FA-20 model: every note is its own blank-named notebook, and the browser card's
            // title is the NOTEBOOK name when set, falling back to the cover page's title only
            // when the name is blank — so create the note the way the app does. (Planting a
            // titled page inside ensureDefaultNotebook() rendered a "My Notes" card, and this
            // test timed out waiting for a "Test Note" title that could never appear.)
            val page = repository.createNote()
            repository.renamePage(page.id, "Test Note")
        }
        setLibrary()

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
