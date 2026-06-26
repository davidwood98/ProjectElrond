package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Defaults + parsing for the FA-14 appearance enums and the to-do workflow status. */
class AppearanceEnumsTest {

    @Test
    fun `appearance enums default correctly`() {
        assertEquals(PenIconStyle.BODY, PenIconStyle.DEFAULT)
        assertEquals(AppAccent.BLUE, AppAccent.DEFAULT)
        assertEquals(PaperStyle.DOTS, PaperStyle.DEFAULT)
        assertEquals(NoteTabsMode.SEPARATE, NoteTabsMode.DEFAULT)
        assertEquals(TodoStatus.TODO, TodoStatus.DEFAULT)
        // FA-17 AI-mark appearance: 17c cluster + colour.
        assertEquals(AiLoaderStyle.CLUSTER, AiLoaderStyle.DEFAULT)
        assertEquals(17, AiLoaderStyle.DEFAULT.number)
        assertEquals(AiColorMode.COLOR, AiColorMode.DEFAULT)
        // AI response units: metric by default.
        assertEquals(UnitSystem.METRIC, UnitSystem.DEFAULT)
    }

    @Test
    fun `fromName falls back to the default for unknown or null`() {
        assertEquals(PenIconStyle.TIP, PenIconStyle.fromName("TIP"))
        assertEquals(PenIconStyle.BODY, PenIconStyle.fromName("nonsense"))
        assertEquals(AppAccent.PINK, AppAccent.fromName("PINK"))
        assertEquals(AppAccent.BLUE, AppAccent.fromName(null))
        assertEquals(PaperStyle.RULED, PaperStyle.fromName("RULED"))
        assertEquals(NoteTabsMode.ATTACHED, NoteTabsMode.fromName("ATTACHED"))
        // FA-17
        assertEquals(AiLoaderStyle.PINCH, AiLoaderStyle.fromName("PINCH"))
        assertEquals(AiLoaderStyle.CLUSTER, AiLoaderStyle.fromName("nonsense"))
        assertEquals(AiColorMode.BLACK, AiColorMode.fromName("BLACK"))
        assertEquals(AiColorMode.COLOR, AiColorMode.fromName(null))
        assertEquals(UnitSystem.IMPERIAL, UnitSystem.fromName("IMPERIAL"))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromName(null))
        assertEquals(UnitSystem.METRIC, UnitSystem.fromName("nonsense"))
    }

    @Test
    fun `AiLoaderStyle exposes the seven designed loader numbers`() {
        assertEquals(
            listOf(2, 5, 7, 11, 14, 15, 17),
            AiLoaderStyle.entries.map { it.number },
        )
    }

    @Test
    fun `TodoStatus fromInt maps ordinals and clamps out-of-range`() {
        assertEquals(TodoStatus.TODO, TodoStatus.fromInt(0))
        assertEquals(TodoStatus.IN_PROGRESS, TodoStatus.fromInt(1))
        assertEquals(TodoStatus.DONE, TodoStatus.fromInt(2))
        assertEquals(TodoStatus.TODO, TodoStatus.fromInt(99)) // out of range → default
        assertEquals(TodoStatus.TODO, TodoStatus.fromInt(null))
    }

    @Test
    fun `TodoStatus isDone only for DONE`() {
        assertTrue(TodoStatus.DONE.isDone)
        assertFalse(TodoStatus.TODO.isDone)
        assertFalse(TodoStatus.IN_PROGRESS.isDone)
    }
}
