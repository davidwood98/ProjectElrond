package ai.elrond.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the shared finger-navigation axis-lock decision (FA-20). */
class FingerNavTest {

    @Test
    fun within_slop_stays_undecided() {
        assertEquals(0, lockAxisOrUndecided(totalDx = 5f, totalDy = -5f, slop = 20f))
        // Exactly at the slop is still undecided (strictly-greater locks).
        assertEquals(0, lockAxisOrUndecided(totalDx = 20f, totalDy = 0f, slop = 20f))
    }

    @Test
    fun dominant_horizontal_locks_to_page_turn() {
        assertEquals(2, lockAxisOrUndecided(totalDx = 30f, totalDy = 10f, slop = 20f))
        assertEquals(2, lockAxisOrUndecided(totalDx = -40f, totalDy = 5f, slop = 20f))
    }

    @Test
    fun dominant_vertical_locks_to_scroll() {
        assertEquals(1, lockAxisOrUndecided(totalDx = 5f, totalDy = 30f, slop = 20f))
        assertEquals(1, lockAxisOrUndecided(totalDx = 10f, totalDy = -50f, slop = 20f))
    }

    @Test
    fun a_lock_can_trip_on_either_axis_past_the_slop() {
        // Vertical past the slop while horizontal is tiny → vertical.
        assertEquals(1, lockAxisOrUndecided(totalDx = 2f, totalDy = 25f, slop = 20f))
        // Equal components resolve to vertical (horizontal is not strictly greater).
        assertEquals(1, lockAxisOrUndecided(totalDx = 25f, totalDy = 25f, slop = 20f))
    }
}
