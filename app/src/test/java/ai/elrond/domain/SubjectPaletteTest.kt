package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure unit tests for the generated subject colour palette. */
class SubjectPaletteTest {

    @Test
    fun `palette has the full 66-colour spectrum`() {
        assertEquals(66, SubjectPalette.SIZE)
        assertEquals(SubjectPalette.SIZE, SubjectPalette.colors.size)
        assertEquals(SubjectPalette.HUE_COUNT * SubjectPalette.SHADE_COUNT, SubjectPalette.SIZE)
    }

    @Test
    fun `every colour is opaque and the set is distinct`() {
        SubjectPalette.colors.forEach { argb ->
            assertEquals("alpha must be fully opaque", 0xFF, (argb ushr 24) and 0xFF)
        }
        assertEquals("colours should be distinct", SubjectPalette.SIZE, SubjectPalette.colors.toSet().size)
    }

    @Test
    fun `argb is in range and normalize wraps any int into the palette`() {
        for (id in 0 until SubjectPalette.SIZE) {
            assertEquals(SubjectPalette.colors[id], SubjectPalette.argb(id))
        }
        // Out-of-range ids wrap rather than crash.
        assertEquals(0, SubjectPalette.normalize(SubjectPalette.SIZE))
        assertEquals(SubjectPalette.SIZE - 1, SubjectPalette.normalize(-1))
        assertTrue(SubjectPalette.normalize(1_000) in 0 until SubjectPalette.SIZE)
    }
}
