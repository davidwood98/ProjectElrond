package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tag colouring (FA-24): same name → same colour, always — and never one of the
 * dark shades the pill text is unreadable on (device feedback).
 */
class TagColorTest {

    @Test
    fun `the same name always resolves to the same colour`() {
        assertEquals(TagColor.forName("physics"), TagColor.forName("physics"))
        assertEquals(TagColor.forName("Physics"), TagColor.forName("Physics"))
    }

    @Test
    fun `every generated colour is a readable subject-palette shade`() {
        val names = listOf("physics", "maths", "history", "a", "b", "c", "zzz", "полка", "日本語")
        names.forEach { name ->
            val argb = TagColor.forName(name)
            assertTrue("$name produced non-palette colour", SubjectPalette.colors.contains(argb))
            assertTrue("$name produced an unreadable shade", TagColor.isReadable(argb))
        }
    }

    @Test
    fun `each hue's darkest shade is excluded`() {
        for (hue in 0 until SubjectPalette.HUE_COUNT) {
            val darkest = SubjectPalette.argb(hue * SubjectPalette.SHADE_COUNT + SubjectPalette.SHADE_COUNT - 1)
            assertFalse("hue $hue darkest shade must be unreadable", TagColor.isReadable(darkest))
        }
    }

    @Test
    fun `a name with a negative hashCode is safe and lands in the readable set`() {
        val negative = "polygenelubricants"
        assertTrue(negative.hashCode() < 0)
        assertTrue(TagColor.isReadable(TagColor.forName(negative)))
    }

    @Test
    fun `the empty string is safe`() {
        assertTrue(TagColor.isReadable(TagColor.forName("")))
    }
}
