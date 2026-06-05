package ai.elrond.data

import ai.elrond.calendar.CalendarEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarRepositoryTest {

    private val dao = mockk<CalendarEventDao>(relaxed = true)
    private val repository = CalendarRepository(
        dao = dao,
        clock = { FIXED_TIME },
        newId = { "evt-1" },
    )

    private fun event() = CalendarEvent(
        title = "Sync with Sarah",
        startTime = 100L,
        endTime = 200L,
        location = "Room 1",
        attendees = listOf("Sarah"),
    )

    @Test
    fun `addSuggestion stores an unconfirmed AI event linked to its note`() = runTest {
        val slot = slot<CalendarEventEntity>()
        coEvery { dao.insert(capture(slot)) } returns Unit

        val id = repository.addSuggestion(event(), sourcePageId = "page-9")

        assertEquals("evt-1", id)
        assertTrue(slot.captured.isAiSuggested)
        assertFalse(slot.captured.isConfirmed)
        assertEquals("page-9", slot.captured.sourcePageId)
        assertEquals("Sync with Sarah", slot.captured.title)
        assertEquals(listOf("Sarah"), slot.captured.attendees)
        assertEquals(FIXED_TIME, slot.captured.createdAt)
    }

    @Test
    fun `confirm marks the event confirmed and records the external id`() = runTest {
        val existing = CalendarEventEntity(
            id = "evt-1",
            title = "Sync",
            startTime = 100L,
            endTime = 200L,
            isAiSuggested = true,
            isConfirmed = false,
            createdAt = 1L,
        )
        coEvery { dao.getById("evt-1") } returns existing
        val slot = slot<CalendarEventEntity>()
        coEvery { dao.update(capture(slot)) } returns Unit

        repository.confirm("evt-1", externalEventId = "device-42")

        assertTrue(slot.captured.isConfirmed)
        assertEquals("device-42", slot.captured.externalEventId)
    }

    @Test
    fun `eventsInRange maps entities to domain events`() = runTest {
        coEvery { dao.inRange(0L, 500L) } returns listOf(
            CalendarEventEntity(
                id = "evt-1", title = "Sync", startTime = 100L, endTime = 200L,
                externalEventId = "device-42", sourcePageId = "page-9",
                isAiSuggested = true, isConfirmed = true, createdAt = 1L,
            ),
        )

        val events = repository.eventsInRange(0L, 500L)

        assertEquals("Sync", events.single().title)
        assertEquals("device-42", events.single().id) // domain id == backing-calendar id
        assertEquals("page-9", events.single().sourceNoteId)
    }

    @Test
    fun `delete delegates to the dao`() = runTest {
        repository.delete("evt-1")
        coVerify { dao.deleteById("evt-1") }
    }

    companion object {
        private const val FIXED_TIME = 1_780_000_000_000
    }
}
