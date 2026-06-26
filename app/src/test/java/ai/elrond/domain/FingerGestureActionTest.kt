package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/** FA-19: parsing for the finger-gesture action enum. */
class FingerGestureActionTest {

    @Test
    fun `fromName round-trips every value`() {
        FingerGestureAction.entries.forEach { action ->
            assertEquals(action, FingerGestureAction.fromName(action.name))
        }
    }

    @Test
    fun `fromName falls back to NONE for null or unknown`() {
        assertEquals(FingerGestureAction.NONE, FingerGestureAction.fromName(null))
        assertEquals(FingerGestureAction.NONE, FingerGestureAction.fromName("nonsense"))
        assertEquals(FingerGestureAction.NONE, FingerGestureAction.fromName(""))
    }
}
