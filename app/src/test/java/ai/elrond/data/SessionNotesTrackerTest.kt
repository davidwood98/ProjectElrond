package ai.elrond.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure unit tests for the in-memory session-notes holder (FA-16). */
class SessionNotesTrackerTest {

    @Test
    fun `recordOpened keeps a stable insertion order and dedups on re-open`() {
        val tracker = SessionNotesTracker()
        tracker.recordOpened("a")
        tracker.recordOpened("b")
        tracker.recordOpened("c")
        tracker.recordOpened("a") // re-opening an existing note must NOT reorder the tabs

        assertEquals(listOf("a", "b", "c"), tracker.openedPageIds.value)
    }

    @Test
    fun `clear resets the session`() {
        val tracker = SessionNotesTracker()
        tracker.recordOpened("a")
        tracker.recordOpened("b")

        tracker.clear()

        assertEquals(emptyList<String>(), tracker.openedPageIds.value)
    }
}
