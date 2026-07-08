package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tag colouring (FA-24): same name → same colour, always. */
class TagColorTest {

    @Test
    fun `the same name always resolves to the same colour`() {
        assertEquals(TagColor.forName("physics"), TagColor.forName("physics"))
        assertEquals(TagColor.forName("Physics"), TagColor.forName("Physics"))
    }

    @Test
    fun `a name with a negative hashCode is safe and lands in the palette`() {
        // "polygenelubricants".hashCode() is famously Int.MIN_VALUE-adjacent territory; any
        // negative-hash name must still normalize into the 66-colour palette.
        val negative = "polygenelubricants"
        assertTrue(negative.hashCode() < 0)
        val argb = TagColor.forName(negative)
        assertTrue(SubjectPalette.colors.contains(argb))
    }

    @Test
    fun `the empty string is safe`() {
        assertTrue(SubjectPalette.colors.contains(TagColor.forName("")))
    }
}
