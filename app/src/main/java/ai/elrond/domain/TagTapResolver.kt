package ai.elrond.domain

/**
 * Pure state machine for a tag pill's tap gesture (FA-24, the prescriptive untag flow):
 *
 * 1. First tap on a pill → [TapOutcome.ENTER_PREVIEW] (the pill shows its full, untruncated
 *    name). Happens for EVERY tag, truncated or not — the gesture's meaning never depends on
 *    whether the label happened to be clipped.
 * 2. A second tap on the SAME pill within [TAP_WINDOW_MS] → [TapOutcome.BEGIN_UNTAG] (the pill
 *    greys out in place; the ViewModel starts the 2s cancellable removal window).
 * 3. Any tap on a greyed (pending-removal) pill → [TapOutcome.CANCEL_UNTAG] (the removal never
 *    reached the DB; the pill re-selects as still-tagged).
 *
 * The window is strict `<` — a tap at exactly [TAP_WINDOW_MS] re-enters preview instead of
 * untagging. Compose-free so the transition table is JVM-testable.
 */
object TagTapResolver {

    /** Matches the app's 300ms double-tap convention (InkCanvas's private DOUBLE_TAP_WINDOW_MS). */
    const val TAP_WINDOW_MS = 300L

    enum class TapOutcome { ENTER_PREVIEW, BEGIN_UNTAG, CANCEL_UNTAG }

    fun resolve(
        nowMs: Long,
        lastTapAtMs: Long,
        selectedTagId: String?,
        tagId: String,
        isPendingRemoval: Boolean,
    ): TapOutcome = when {
        isPendingRemoval -> TapOutcome.CANCEL_UNTAG
        selectedTagId == tagId && nowMs - lastTapAtMs < TAP_WINDOW_MS -> TapOutcome.BEGIN_UNTAG
        else -> TapOutcome.ENTER_PREVIEW
    }
}
