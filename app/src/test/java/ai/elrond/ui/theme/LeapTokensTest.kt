package ai.elrond.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * The selected-tile accent shades must match the handoff's CSS `color-mix(in srgb, …)`:
 *   --acc-soft   = color-mix(in srgb, accent 16%, #fff)
 *   --acc-strong = color-mix(in srgb, accent 74%, #11181c)
 * Expected values computed for accent = Leap Blue (#52C6DA).
 */
@RunWith(RobolectricTestRunner::class)
class LeapTokensTest {

    @Test
    fun `mixSrgb endpoints return the pure colours`() {
        assertEquals(LeapBlue.toArgb(), mixSrgb(LeapBlue, LeapWhite, 1f).toArgb())
        assertEquals(LeapWhite.toArgb(), mixSrgb(LeapBlue, LeapWhite, 0f).toArgb())
    }

    @Test
    fun `soft and strong accent shades match the handoff color-mix`() {
        val tokens = LeapTokens() // accent = Leap Blue

        val soft = tokens.accentSoft.toArgb()
        assertChannel("soft.r", soft, 16, 227)
        assertChannel("soft.g", soft, 8, 246)
        assertChannel("soft.b", soft, 0, 249)

        val strong = tokens.accentStrong.toArgb()
        assertChannel("strong.r", strong, 16, 65)
        assertChannel("strong.g", strong, 8, 153)
        assertChannel("strong.b", strong, 0, 169)
    }

    private fun assertChannel(name: String, argb: Int, shift: Int, expected: Int) {
        val actual = (argb shr shift) and 0xFF
        assertTrue("$name expected ~$expected but was $actual", abs(actual - expected) <= 1)
    }
}
