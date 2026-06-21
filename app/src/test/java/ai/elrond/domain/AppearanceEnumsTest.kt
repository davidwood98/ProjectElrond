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
    }

    @Test
    fun `fromName falls back to the default for unknown or null`() {
        assertEquals(PenIconStyle.TIP, PenIconStyle.fromName("TIP"))
        assertEquals(PenIconStyle.BODY, PenIconStyle.fromName("nonsense"))
        assertEquals(AppAccent.PINK, AppAccent.fromName("PINK"))
        assertEquals(AppAccent.BLUE, AppAccent.fromName(null))
        assertEquals(PaperStyle.RULED, PaperStyle.fromName("RULED"))
        assertEquals(NoteTabsMode.ATTACHED, NoteTabsMode.fromName("ATTACHED"))
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
