package ai.elrond.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** FA-23 device feedback: straight-line angle snap (±2° in, 5° hysteresis out, 45° steps). */
class StraightLineSnapTest {

    @Test
    fun `snaps within two degrees of a 45 multiple`() {
        assertEquals(0f, StraightLineSnap.updateSnap(null, 1.5f))
        assertEquals(45f, StraightLineSnap.updateSnap(null, 43.8f))
        assertEquals(90f, StraightLineSnap.updateSnap(null, -270f + 1f)) // wrapped input
        assertEquals(180f, StraightLineSnap.updateSnap(null, -179.2f)) // the ±180 seam
    }

    @Test
    fun `does not snap outside the snap-in window`() {
        assertNull(StraightLineSnap.updateSnap(null, 3f))
        assertNull(StraightLineSnap.updateSnap(null, 40f))
        assertNull(StraightLineSnap.updateSnap(null, 22.5f)) // exactly between two targets
    }

    @Test
    fun `stays snapped until deviation reaches five degrees`() {
        val snapped = StraightLineSnap.updateSnap(null, 1f) // snap to 0
        assertEquals(0f, snapped)
        assertEquals(0f, StraightLineSnap.updateSnap(snapped, 4.9f)) // inside hysteresis: held
        assertEquals(0f, StraightLineSnap.updateSnap(snapped, -4.9f))
        assertNull(StraightLineSnap.updateSnap(snapped, 5f)) // breaks out exactly at 5°
        assertNull(StraightLineSnap.updateSnap(snapped, -6f))
    }

    @Test
    fun `after breaking out it can snap straight onto another target`() {
        var snap = StraightLineSnap.updateSnap(null, 44f) // 45
        assertEquals(45f, snap)
        snap = StraightLineSnap.updateSnap(snap, 89f) // far past hysteresis, within 2° of 90
        assertEquals(90f, snap)
    }

    @Test
    fun `projection keeps the along-axis component of the drag`() {
        // Snapped horizontal from (10, 10): a drag to (110, 14) lands at (110, 10).
        val (x, y) = StraightLineSnap.projectEndpoint(10f, 10f, 110f, 14f, 0f)
        assertEquals(110f, x, 0.001f)
        assertEquals(10f, y, 0.001f)
        // Snapped vertical (90° = +y down the screen).
        val (vx, vy) = StraightLineSnap.projectEndpoint(0f, 0f, 3f, 80f, 90f)
        assertEquals(0f, vx, 0.001f)
        assertEquals(80f, vy, 0.001f)
        // Snapped 45° diagonal: (70, 72) projects onto y = x.
        val (dx, dy) = StraightLineSnap.projectEndpoint(0f, 0f, 70f, 72f, 45f)
        assertEquals(dx, dy, 0.001f)
        assertEquals(71f, dx, 0.01f)
    }
}
