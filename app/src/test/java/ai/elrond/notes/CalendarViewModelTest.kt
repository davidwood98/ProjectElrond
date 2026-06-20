package ai.elrond.notes

import ai.elrond.presentation.CalendarViewModel
import ai.elrond.data.NoteRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val zone = ZoneId.of("UTC")
    private val repository = mockk<NoteRepository>(relaxed = true)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun millis(date: String) =
        ZonedDateTime.of(LocalDate.parse(date).atTime(12, 0), zone).toInstant().toEpochMilli()

    private fun page(id: String, created: String, modified: String = created) = NotePage(
        id = id, notebookId = "nb", customTitle = null,
        createdAt = millis(created), modifiedAt = millis(modified),
    )

    @Test
    fun `activityByDay reflects creations and per-day edit events`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(
            listOf(page("a", "2026-06-10"), page("b", created = "2026-06-09")),
        )
        // Note b was edited on 2026-06-10 (a day after it was created).
        every { repository.observeEditEvents() } returns flowOf(
            listOf(NoteEditDay("b", LocalDate.parse("2026-06-10"))),
        )
        val viewModel = CalendarViewModel(repository, zone)

        backgroundScope.launch { viewModel.activityByDay.collect { } }
        backgroundScope.launch { viewModel.hasAnyNotes.collect { } }
        advanceUntilIdle()

        val day = viewModel.activityByDay.value.getValue(LocalDate.parse("2026-06-10"))
        assertEquals(1, day.created) // note a created
        assertEquals(1, day.edited) // note b edited
        assertTrue(viewModel.hasAnyNotes.value)
    }

    @Test
    fun `notesForDay filters by the chosen date`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(
            listOf(page("a", "2026-06-10"), page("b", "2026-06-08")),
        )
        every { repository.observeEditEvents() } returns flowOf(emptyList())
        val viewModel = CalendarViewModel(repository, zone)
        backgroundScope.launch { viewModel.activityByDay.collect { } }
        advanceUntilIdle()

        assertEquals(listOf("a"), viewModel.notesForDay(LocalDate.parse("2026-06-10")).map { it.id })
    }

    @Test
    fun `notesForDay includes notes edited on a later day`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(listOf(page("a", "2026-06-08")))
        every { repository.observeEditEvents() } returns flowOf(
            listOf(NoteEditDay("a", LocalDate.parse("2026-06-10"))),
        )
        val viewModel = CalendarViewModel(repository, zone)
        backgroundScope.launch { viewModel.activityByDay.collect { } }
        advanceUntilIdle()

        assertEquals(listOf("a"), viewModel.notesForDay(LocalDate.parse("2026-06-10")).map { it.id })
    }

    @Test
    fun `createNote bootstraps a notebook and reports the new page id`() = runTest(dispatcher) {
        every { repository.observeTimeline() } returns flowOf(emptyList())
        every { repository.observeEditEvents() } returns flowOf(emptyList())
        coEvery { repository.ensureDefaultNotebook() } returns Notebook("nb", "My Notes", 1L)
        coEvery { repository.createPage("nb") } returns page("new", "2026-06-10")
        val viewModel = CalendarViewModel(repository, zone)

        var created: String? = null
        viewModel.createNote { created = it }
        advanceUntilIdle()

        assertEquals("new", created)
    }
}
