package ai.elrond.calendar

import ai.elrond.notes.NotePage
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteActivityMapperTest {

    private val zone = ZoneId.of("UTC")

    private fun millis(date: String, hour: Int = 12): Long =
        ZonedDateTime.of(LocalDate.parse(date).atTime(hour, 0), zone).toInstant().toEpochMilli()

    private fun page(id: String, created: String, modified: String = created) = NotePage(
        id = id,
        notebookId = "nb",
        customTitle = null,
        createdAt = millis(created),
        modifiedAt = millis(modified),
    )

    @Test
    fun `a note created and edited the same day counts only as created`() {
        val map = NoteActivityMapper.activityByDay(
            listOf(page("a", "2026-06-10", modified = "2026-06-10")),
            zone,
        )
        val day = LocalDate.parse("2026-06-10")
        assertEquals(1, map.getValue(day).created)
        assertEquals(0, map.getValue(day).edited)
    }

    @Test
    fun `an edit on a later day marks that day as edited`() {
        val map = NoteActivityMapper.activityByDay(
            listOf(page("a", created = "2026-06-10", modified = "2026-06-12")),
            zone,
        )
        assertTrue(map.getValue(LocalDate.parse("2026-06-10")).hasCreated)
        assertTrue(map.getValue(LocalDate.parse("2026-06-12")).hasEdited)
        assertEquals(0, map.getValue(LocalDate.parse("2026-06-12")).created)
    }

    @Test
    fun `counts accumulate across notes on the same day`() {
        val map = NoteActivityMapper.activityByDay(
            listOf(page("a", "2026-06-10"), page("b", "2026-06-10"), page("c", "2026-06-10")),
            zone,
        )
        assertEquals(3, map.getValue(LocalDate.parse("2026-06-10")).created)
    }

    @Test
    fun `notesForDay returns notes created or edited on the date`() {
        val pages = listOf(
            page("a", created = "2026-06-10"),
            page("b", created = "2026-06-09", modified = "2026-06-10"),
            page("c", created = "2026-06-08"),
        )
        val notes = NoteActivityMapper.notesForDay(pages, LocalDate.parse("2026-06-10"), zone)
        assertEquals(setOf("a", "b"), notes.map { it.id }.toSet())
    }
}
