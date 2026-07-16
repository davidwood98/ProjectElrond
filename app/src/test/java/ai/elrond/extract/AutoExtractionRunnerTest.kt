package ai.elrond.extract

import ai.elrond.domain.SuggestionType
import ai.elrond.domain.RecognizedLine
import ai.elrond.domain.AutoExtractionRunner
import ai.elrond.aibackend.CalendarEventExtractor
import ai.elrond.aibackend.ExtractedEvent
import ai.elrond.aibackend.ExtractedTask
import ai.elrond.aibackend.TaskExtractor
import ai.elrond.data.CalendarRepository
import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NotePageEntity
import ai.elrond.data.NotebookEntity
import ai.elrond.data.SuggestionRepository
import ai.elrond.data.TodoRepository
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneId

/**
 * Routing + de-dup behaviour of [AutoExtractionRunner] against real Room repositories
 * (Robolectric in-memory), with the ink/ML-Kit recognition and AI extractors faked.
 */
@RunWith(RobolectricTestRunner::class)
class AutoExtractionRunnerTest {

    private lateinit var db: ElrondDatabase
    private lateinit var todoRepository: TodoRepository
    private lateinit var calendarRepository: CalendarRepository
    private lateinit var suggestionRepository: SuggestionRepository
    private var badgeFlagged = false

    @Before
    fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, ElrondDatabase::class.java).allowMainThreadQueries().build()
        db.notebookDao().insert(NotebookEntity(id = "nb1", name = "N", createdAt = 0))
        db.notePageDao().insert(
            NotePageEntity(id = "p1", notebookId = "nb1", customTitle = "Note", createdAt = 0, modifiedAt = 0),
        )
        todoRepository = TodoRepository(db.todoDao())
        calendarRepository = CalendarRepository(db.calendarEventDao())
        suggestionRepository = SuggestionRepository(db.pendingSuggestionDao())
        badgeFlagged = false
    }

    @After
    fun tearDown() = db.close()

    private fun runner(
        tasks: List<ExtractedTask> = emptyList(),
        events: List<ExtractedEvent> = emptyList(),
        lines: List<RecognizedLine> = listOf(RecognizedLine("note text", 0f, 0f, 100f, 20f)),
    ) = AutoExtractionRunner(
        recognizeLines = { lines },
        taskExtractor = fakeTasks(tasks),
        eventExtractor = fakeEvents(events),
        todoRepository = todoRepository,
        calendarRepository = calendarRepository,
        suggestionRepository = suggestionRepository,
        resolvePageTitle = { "Note" },
        markNewTodoItems = { badgeFlagged = true },
        clock = { 0L },
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun `confirmation on creates a pending TODO suggestion and adds nothing directly`() = runTest {
        runner(tasks = listOf(ExtractedTask("Buy milk", priority = 2)))
            .run("p1", confirmTodo = true, confirmCalendar = true)

        val pending = suggestionRepository.observePending("p1").first()
        assertEquals(1, pending.size)
        assertEquals(SuggestionType.TODO, pending.single().type)
        assertEquals("Buy milk", pending.single().content)
        assertTrue(todoRepository.observeAll().first().isEmpty())
        assertFalse(badgeFlagged)
    }

    @Test
    fun `confirmation off adds the task directly and flags the to-do tab`() = runTest {
        runner(tasks = listOf(ExtractedTask("Buy milk")))
            .run("p1", confirmTodo = false, confirmCalendar = false)

        assertEquals(listOf("Buy milk"), todoRepository.observeAll().first().map { it.content })
        assertTrue(badgeFlagged)
        assertTrue(suggestionRepository.observePending("p1").first().isEmpty())
    }

    @Test
    fun `a task already on the to-do list is not re-suggested`() = runTest {
        todoRepository.addManual("Buy milk")
        runner(tasks = listOf(ExtractedTask("buy milk")))
            .run("p1", confirmTodo = true, confirmCalendar = true)

        assertTrue(suggestionRepository.observePending("p1").first().isEmpty())
    }

    @Test
    fun `an accepted suggestion is not re-suggested when the same note is saved again`() = runTest {
        // First save (confirmation on) raises a pending TODO suggestion.
        runner(tasks = listOf(ExtractedTask("Buy milk", priority = 2)))
            .run("p1", confirmTodo = true, confirmCalendar = true)
        val accepted = suggestionRepository.observePending("p1").first().single()

        // User accepts: the item becomes a to-do and the suggestion row is marked handled (kept).
        todoRepository.addExtracted(
            listOf(TodoRepository.ExtractedTask("Buy milk")),
            sourcePageId = "p1",
            sourcePageTitle = "Note",
        )
        suggestionRepository.markHandled(accepted.id)

        // Re-enter the note and save again with the same line still present → no new popup.
        runner(tasks = listOf(ExtractedTask("Buy milk", priority = 2)))
            .run("p1", confirmTodo = true, confirmCalendar = true)

        assertTrue(suggestionRepository.observePending("p1").first().isEmpty())
    }

    @Test
    fun `a handled suggestion de-dups future saves even without a matching to-do`() = runTest {
        // Handled (kept) suggestion with no matching to-do — e.g. accepted then the to-do deleted.
        runner(tasks = listOf(ExtractedTask("Buy milk")))
            .run("p1", confirmTodo = true, confirmCalendar = true)
        val s = suggestionRepository.observePending("p1").first().single()
        suggestionRepository.markHandled(s.id)

        // Same line resurfaces on a later save → still not re-suggested (handled rows de-dup).
        runner(tasks = listOf(ExtractedTask("Buy milk")))
            .run("p1", confirmTodo = true, confirmCalendar = true)

        assertTrue(suggestionRepository.observePending("p1").first().isEmpty())
    }

    @Test
    fun `duplicated identical content yields only one suggestion`() = runTest {
        // A pasted/duplicated to-do shows up twice in the recognized text and is extracted twice;
        // the runner's type-namespaced de-dup collapses them to a single suggestion — so a lasso
        // duplicate/paste can never produce a doubled item even if it did reach the extractor.
        runner(tasks = listOf(ExtractedTask("Buy milk"), ExtractedTask("buy milk")))
            .run("p1", confirmTodo = true, confirmCalendar = true)

        assertEquals(1, suggestionRepository.observePending("p1").first().size)
    }

    @Test
    fun `confirmation off creates a calendar suggestion for a dated event`() = runTest {
        runner(events = listOf(ExtractedEvent("Standup", startIso = "2026-06-10T15:00")))
            .run("p1", confirmTodo = false, confirmCalendar = false)

        val events = calendarRepository.observeAll().first()
        assertEquals(listOf("Standup"), events.map { it.title })
        assertTrue(events.single().isAiSuggested)
    }

    @Test
    fun `an event whose title matches a task content is not dropped by de-dup`() = runTest {
        runner(
            tasks = listOf(ExtractedTask("Standup")),
            events = listOf(ExtractedEvent("Standup", startIso = "2026-06-10T15:00")),
        ).run("p1", confirmTodo = false, confirmCalendar = false)

        assertEquals(listOf("Standup"), todoRepository.observeAll().first().map { it.content })
        assertEquals(listOf("Standup"), calendarRepository.observeAll().first().map { it.title })
    }

    @Test
    fun `an event without a time is skipped`() = runTest {
        runner(events = listOf(ExtractedEvent("Someday maybe", startIso = null)))
            .run("p1", confirmTodo = true, confirmCalendar = true)

        assertTrue(suggestionRepository.observePending("p1").first().none { it.type == SuggestionType.EVENT })
        assertTrue(calendarRepository.observeAll().first().isEmpty())
    }

    // --- FA-24b skip-gate ---

    /** Counts extractor invocations so a skipped run is observable. */
    private class CountingTasks(private val tasks: List<ExtractedTask>) : TaskExtractor {
        var calls = 0
        override suspend fun extract(noteContent: String, referenceDate: String?): Result<List<ExtractedTask>> {
            calls++
            return Result.success(tasks)
        }
    }

    private fun gatedRunner(
        taskExtractor: TaskExtractor,
        loadLastText: suspend (String) -> String?,
        saveLastText: suspend (String, String) -> Unit = { _, _ -> },
        lines: List<RecognizedLine> = listOf(RecognizedLine("note text", 0f, 0f, 100f, 20f)),
    ) = AutoExtractionRunner(
        recognizeLines = { lines },
        taskExtractor = taskExtractor,
        eventExtractor = fakeEvents(emptyList()),
        todoRepository = todoRepository,
        calendarRepository = calendarRepository,
        suggestionRepository = suggestionRepository,
        resolvePageTitle = { "Note" },
        markNewTodoItems = { badgeFlagged = true },
        loadLastText = loadLastText,
        saveLastText = saveLastText,
        clock = { 0L },
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun `unchanged page text skips extraction entirely (zero extractor calls)`() = runTest {
        val tasks = CountingTasks(listOf(ExtractedTask("Buy milk")))
        // loadLastText returns exactly the assembled fullText ("note text") → gate closes.
        gatedRunner(tasks, loadLastText = { "note text" })
            .run("p1", confirmTodo = false, confirmCalendar = false)

        assertEquals(0, tasks.calls)
        assertTrue(todoRepository.observeAll().first().isEmpty())
    }

    @Test
    fun `first run with no prior text always extracts`() = runTest {
        val tasks = CountingTasks(listOf(ExtractedTask("Buy milk")))
        // loadLastText returns null (never run) → must never be treated as "unchanged".
        gatedRunner(tasks, loadLastText = { null })
            .run("p1", confirmTodo = false, confirmCalendar = false)

        assertEquals(1, tasks.calls)
        assertEquals(listOf("Buy milk"), todoRepository.observeAll().first().map { it.content })
    }

    @Test
    fun `changed page text extracts and persists the new text via saveLastText`() = runTest {
        val tasks = CountingTasks(listOf(ExtractedTask("Buy milk")))
        var saved: Pair<String, String>? = null
        gatedRunner(
            tasks,
            loadLastText = { "an older version of the page" },
            saveLastText = { id, text -> saved = id to text },
        ).run("p1", confirmTodo = false, confirmCalendar = false)

        assertEquals(1, tasks.calls)
        assertEquals("p1" to "note text", saved)
    }

    private fun fakeTasks(tasks: List<ExtractedTask>) = object : TaskExtractor {
        override suspend fun extract(noteContent: String, referenceDate: String?) = Result.success(tasks)
    }

    private fun fakeEvents(events: List<ExtractedEvent>) = object : CalendarEventExtractor {
        override suspend fun extract(noteContent: String, referenceDate: String?) = Result.success(events)
    }
}
