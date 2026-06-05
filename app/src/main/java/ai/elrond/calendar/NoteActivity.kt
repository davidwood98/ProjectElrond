package ai.elrond.calendar

import ai.elrond.notes.NotePage
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Note activity on a single day: how many notes were created and edited. */
data class DayActivity(val created: Int = 0, val edited: Int = 0) {
    val hasCreated: Boolean get() = created > 0
    val hasEdited: Boolean get() = edited > 0
    val isEmpty: Boolean get() = created == 0 && edited == 0
}

/**
 * Pure mapping from note pages to per-day activity for the calendar view.
 *
 * A note counts as **created** on its creation day and, only if its last edit
 * fell on a different day, as **edited** on that later day. (We store just the
 * last-modified time, so multi-day edit history isn't reconstructable — noted.)
 */
object NoteActivityMapper {

    fun activityByDay(pages: List<NotePage>, zone: ZoneId = ZoneId.systemDefault()): Map<LocalDate, DayActivity> {
        data class Counts(var created: Int = 0, var edited: Int = 0)
        val acc = HashMap<LocalDate, Counts>()
        pages.forEach { page ->
            val createdDay = page.createdAt.toLocalDate(zone)
            acc.getOrPut(createdDay) { Counts() }.created++
            val modifiedDay = page.modifiedAt.toLocalDate(zone)
            if (modifiedDay != createdDay) acc.getOrPut(modifiedDay) { Counts() }.edited++
        }
        return acc.mapValues { (_, c) -> DayActivity(c.created, c.edited) }
    }

    /** Notes created OR last-edited on [date]. */
    fun notesForDay(pages: List<NotePage>, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<NotePage> =
        pages.filter { page ->
            page.createdAt.toLocalDate(zone) == date || page.modifiedAt.toLocalDate(zone) == date
        }

    private fun Long.toLocalDate(zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDate()
}

/** Pure helpers that build the date grids for the month and week views. */
object CalendarGrid {

    /**
     * A full month grid padded to whole weeks (Monday-first). Includes the
     * trailing days of the previous month and leading days of the next so each
     * row has 7 entries; callers compare `date.month` to know which are in-month.
     */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate> {
        val first = month.atDay(1)
        val lead = ((first.dayOfWeek.value - firstDay.value) + 7) % 7
        val gridStart = first.minusDays(lead.toLong())
        val last = month.atEndOfMonth()
        val trail = ((firstDay.value + 6 - last.dayOfWeek.value) + 7) % 7
        val gridEnd = last.plusDays(trail.toLong())
        val days = (0..java.time.temporal.ChronoUnit.DAYS.between(gridStart, gridEnd)).map {
            gridStart.plusDays(it)
        }
        return days
    }

    /** The 7 days of the week containing [date], Monday-first. */
    fun weekDays(date: LocalDate, firstDay: DayOfWeek = DayOfWeek.MONDAY): List<LocalDate> {
        val back = ((date.dayOfWeek.value - firstDay.value) + 7) % 7
        val start = date.minusDays(back.toLong())
        return (0..6).map { start.plusDays(it.toLong()) }
    }
}
