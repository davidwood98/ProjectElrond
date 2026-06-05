package ai.elrond.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarGridTest {

    @Test
    fun `month grid is whole weeks and contains every day of the month`() {
        val grid = CalendarGrid.monthGrid(YearMonth.of(2026, 6)) // June 2026
        assertEquals(0, grid.size % 7) // padded to whole weeks
        assertTrue(grid.first().dayOfWeek == DayOfWeek.MONDAY)
        (1..30).forEach { d ->
            assertTrue("missing June $d", grid.contains(LocalDate.of(2026, 6, d)))
        }
    }

    @Test
    fun `month grid starts on or before the first of the month`() {
        val grid = CalendarGrid.monthGrid(YearMonth.of(2026, 6))
        assertTrue(grid.first() <= LocalDate.of(2026, 6, 1))
        assertTrue(grid.last() >= LocalDate.of(2026, 6, 30))
    }

    @Test
    fun `week days returns the seven days of the week monday-first`() {
        val week = CalendarGrid.weekDays(LocalDate.of(2026, 6, 10)) // a Wednesday
        assertEquals(7, week.size)
        assertEquals(DayOfWeek.MONDAY, week.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 6, 8), week.first())
        assertEquals(LocalDate.of(2026, 6, 14), week.last())
    }
}
