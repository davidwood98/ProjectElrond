package ai.elrond.ui

import kotlin.math.abs

/**
 * Shared finger-navigation gesture geometry (FA-20). The lone-finger drag that scrolls vertically or
 * turns pages horizontally is detected in two input worlds — the `InkCanvas` `View.OnTouchListener`
 * (raw `MotionEvent`s) and the Compose `SelectionLayer.LassoCatcher` (`awaitPointerEvent`) — which
 * can't share an event pipeline, but they MUST agree on the axis-lock decision. Keeping the slop and
 * the pick here (instead of a constant + `if` copy-pasted in both) removes the risk of the two drifting
 * apart and makes the one branchable rule unit-testable.
 */

/** How far the drag travels (px) before the scroll/page-turn axis locks. */
internal const val FINGER_AXIS_LOCK_SLOP_PX = 20f

/**
 * Resolves the gesture axis from the total drag so far: 0 = still undecided (within the slop), 1 =
 * vertical (scroll), 2 = horizontal (page turn). The dominant axis wins once either component passes
 * [slop].
 */
internal fun lockAxisOrUndecided(
    totalDx: Float,
    totalDy: Float,
    slop: Float = FINGER_AXIS_LOCK_SLOP_PX,
): Int = when {
    abs(totalDx) <= slop && abs(totalDy) <= slop -> 0
    abs(totalDx) > abs(totalDy) -> 2
    else -> 1
}
