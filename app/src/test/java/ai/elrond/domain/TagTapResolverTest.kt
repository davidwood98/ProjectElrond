package ai.elrond.domain

import ai.elrond.domain.TagTapResolver.TapOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** The tag pill tap state machine (FA-24): preview → untag-within-window → cancel-while-greyed. */
class TagTapResolverTest {

    private fun resolve(
        now: Long,
        lastTapAt: Long = 0L,
        selected: String? = null,
        tagId: String = "t1",
        pending: Boolean = false,
    ) = TagTapResolver.resolve(now, lastTapAt, selected, tagId, pending)

    @Test
    fun `first tap enters preview`() {
        assertEquals(TapOutcome.ENTER_PREVIEW, resolve(now = 1_000L))
    }

    @Test
    fun `second tap on the same pill within the window begins the untag`() {
        assertEquals(
            TapOutcome.BEGIN_UNTAG,
            resolve(now = 1_299L, lastTapAt = 1_000L, selected = "t1"),
        )
    }

    @Test
    fun `a tap at exactly the window boundary re-enters preview (strict less-than)`() {
        assertEquals(
            TapOutcome.ENTER_PREVIEW,
            resolve(now = 1_000L + TagTapResolver.TAP_WINDOW_MS, lastTapAt = 1_000L, selected = "t1"),
        )
    }

    @Test
    fun `a quick tap on a DIFFERENT pill previews it instead of untagging`() {
        assertEquals(
            TapOutcome.ENTER_PREVIEW,
            resolve(now = 1_100L, lastTapAt = 1_000L, selected = "t2", tagId = "t1"),
        )
    }

    @Test
    fun `any tap on a greyed (pending-removal) pill cancels the untag`() {
        // Even a slow tap, and even when the pill was never the selected one.
        assertEquals(
            TapOutcome.CANCEL_UNTAG,
            resolve(now = 99_999L, lastTapAt = 0L, selected = null, pending = true),
        )
    }
}
