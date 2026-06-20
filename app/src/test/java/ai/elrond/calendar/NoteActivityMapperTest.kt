package ai.elrond.calendar

import ai.elrond.domain.NoteActivityMapper
import ai.elrond.domain.NoteEditDay
import ai.elrond.domain.NotePage
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

    private fun page(id: String, created: String) = NotePage(
        id = id,
        notebookId = "nb",
        customTitle = null,
        createdAt = millis(created),
        modifiedAt = millis(created),
    )

    private fun edit(pageId: String, date: String) = NoteEditDay(pageId, LocalDate.parse(date))

    @Test
    fun `a note with no edit events counts only as created`() {
        val map = NoteActivityMapper.activityByDay(listOf(page("a", "2026-06-10")), zone = zone)
        val day = LocalDate.parse("2026-06-10")
        assertEquals(1, map.getValue(day).created)
        assertEquals(0, map.getValue(day).edited)
    }

    @Test
    fun `an edit on the creation day is never counted as edited`() {
        val map = NoteActivityMapper.activityByDay(
            listOf(page("a", "2026-06-10")),
            listOf(edit("a", "2026-06-10")),
            zone,
        )
        val day = LocalDate.parse("2026-06-10")
        assertEquals(1, map.getValue(day).created)
        assertEquals(0, map.getValue(day).edited)
    }

    @Test
    fun `edits are tracked on every distinct day after creation`() {
        val map = NoteActivityMapper.activityByDay(
            listOf(page("a", created = "2026-06-10")),
            listOf(edit("a", "2026-06-12"), edit("a", "2026-06-15"), edit("a", "2026-06-12")),
            zone,
        )
        assertTrue(map.getValue(LocalDate.parse("2026-06-10")).hasCreated)
        assertEquals(1, map.getValue(LocalDate.parse("2026-06-12")).edited) // deduped per day
        assertEquals(1, map.getValue(LocalDate.parse("2026-06-15")).edited)
        assertEquals(0, map.getValue(LocalDate.parse("2026-06-12")).created)
    }

    @Test
    fun `counts accumulate across notes on the same day`() {
        val map = NoteActivityMapper.activityByDay(
            listOf(page("a", "2026-06-10"), page("b", "2026-06-10"), page("c", "2026-06-10")),
            zone = zone,
        )
        assertEquals(3, map.getValue(LocalDate.parse("2026-06-10")).created)
    }

    @Test
    fun `notesForDay returns notes created or edited on the date`() {
        val pages = listOf(
            page("a", created = "2026-06-10"),
            page("b", created = "2026-06-09"),
            page("c", created = "2026-06-08"),
        )
        val edits = listOf(edit("b", "2026-06-10"))
        val notes = NoteActivityMapper.notesForDay(pages, LocalDate.parse("2026-06-10"), edits, zone)
        assertEquals(setOf("a", "b"), notes.map { it.id }.toSet())
    }
}
