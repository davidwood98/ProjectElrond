package ai.elrond.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RelativeDateResolverTest {

    // Friday, 5 June 2026 — the reference date from the relative-date bug report.
    private val reference = LocalDate.of(2026, 6, 5)

    @Test
    fun `this Monday resolves to the upcoming Monday`() {
        assertEquals(LocalDate.of(2026, 6, 8), RelativeDateResolver.resolve("this Monday", reference))
    }

    @Test
    fun `next Monday resolves to the following week's Monday`() {
        assertEquals(LocalDate.of(2026, 6, 15), RelativeDateResolver.resolve("next Monday", reference))
    }

    @Test
    fun `today tomorrow and yesterday resolve relative to the reference`() {
        assertEquals(reference, RelativeDateResolver.resolve("today", reference))
        assertEquals(LocalDate.of(2026, 6, 6), RelativeDateResolver.resolve("tomorrow", reference))
        assertEquals(LocalDate.of(2026, 6, 4), RelativeDateResolver.resolve("yesterday", reference))
    }

    @Test
    fun `a bare weekday is the upcoming occurrence`() {
        assertEquals(LocalDate.of(2026, 6, 8), RelativeDateResolver.resolve("monday", reference))
    }

    @Test
    fun `this weekday equal to today is today and next is a week ahead`() {
        // Reference is a Friday.
        assertEquals(reference, RelativeDateResolver.resolve("this Friday", reference))
        assertEquals(LocalDate.of(2026, 6, 12), RelativeDateResolver.resolve("next Friday", reference))
    }

    @Test
    fun `resolution is case and whitespace insensitive`() {
        assertEquals(LocalDate.of(2026, 6, 15), RelativeDateResolver.resolve("  NEXT   Monday ", reference))
        assertEquals(LocalDate.of(2026, 6, 8), RelativeDateResolver.resolve("Mon", reference))
    }

    @Test
    fun `absolute iso dates and unknown text are not relative phrases`() {
        assertNull(RelativeDateResolver.resolve("2026-06-08", reference))
        assertNull(RelativeDateResolver.resolve("someday", reference))
        assertNull(RelativeDateResolver.resolve("", reference))
        assertNull(RelativeDateResolver.resolve(null, reference))
    }
}
