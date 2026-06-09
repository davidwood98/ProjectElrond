package ai.elrond.canvas

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalmRejectionTest {

    @Test
    fun `finger is rejected only while stylus-only mode is on`() {
        assertTrue(PalmRejection.shouldReject(isFinger = true, stylusOnly = true))
        assertFalse(PalmRejection.shouldReject(isFinger = true, stylusOnly = false))
    }

    @Test
    fun `stylus and eraser touches are never rejected`() {
        assertFalse(PalmRejection.shouldReject(isFinger = false, stylusOnly = true))
        assertFalse(PalmRejection.shouldReject(isFinger = false, stylusOnly = false))
    }
}
