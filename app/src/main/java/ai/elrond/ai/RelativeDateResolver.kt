package ai.elrond.ai

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Resolves common relative date phrases ("today", "tomorrow", "this Monday",
 * "next Friday") against a reference date, deterministically — so a relative due
 * date never depends on the model guessing what "today" is.
 *
 * Convention: "this <weekday>" is the soonest upcoming occurrence of that weekday
 * (today, if today already is that weekday); "next <weekday>" is the one a week
 * after that. With reference Friday 2026-06-05: "this Monday" → 2026-06-08 and
 * "next Monday" → 2026-06-15.
 */
object RelativeDateResolver {

    /** @return the resolved date, or null if [phrase] isn't a recognised relative phrase. */
    fun resolve(phrase: String?, today: LocalDate): LocalDate? {
        val normalized = phrase?.trim()?.lowercase().orEmpty()
        if (normalized.isEmpty()) return null
        when (normalized) {
            "today", "tonight" -> return today
            "tomorrow" -> return today.plusDays(1)
            "yesterday" -> return today.minusDays(1)
        }

        // [this|next|coming] <weekday>, or a bare <weekday> (treated as the upcoming one).
        val tokens = normalized.split(WHITESPACE)
        val (modifier, dayToken) = when (tokens.size) {
            1 -> null to tokens[0]
            2 -> tokens[0] to tokens[1]
            else -> return null
        }
        val weekday = weekdayOf(dayToken) ?: return null
        val daysAhead = ((weekday.value - today.dayOfWeek.value) + 7) % 7
        val upcoming = today.plusDays(daysAhead.toLong())
        return when (modifier) {
            null, "this", "coming" -> upcoming
            "next" -> upcoming.plusDays(7)
            else -> null
        }
    }

    private fun weekdayOf(token: String): DayOfWeek? = when (token) {
        "monday", "mon" -> DayOfWeek.MONDAY
        "tuesday", "tue", "tues" -> DayOfWeek.TUESDAY
        "wednesday", "wed" -> DayOfWeek.WEDNESDAY
        "thursday", "thu", "thur", "thurs" -> DayOfWeek.THURSDAY
        "friday", "fri" -> DayOfWeek.FRIDAY
        "saturday", "sat" -> DayOfWeek.SATURDAY
        "sunday", "sun" -> DayOfWeek.SUNDAY
        else -> null
    }

    private val WHITESPACE = Regex("\\s+")
}
