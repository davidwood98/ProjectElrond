package ai.elrond.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Angle snapping for the hold-to-straighten line (FA-23 device feedback): while the endpoint is
 * being adjusted, the line snaps to the horizontal / vertical / diagonal directions (multiples of
 * 45°) when the raw pen angle comes within [SNAP_IN_DEG] of one, and stays snapped until the pen
 * deviates by at least [SNAP_OUT_DEG] — hysteresis, so the line doesn't flicker at the boundary.
 *
 * Pure geometry (JVM-tested); [ai.elrond.presentation.CanvasViewModel] holds the current snapped
 * angle across `updateStraightLine` calls and projects the endpoint through [projectEndpoint].
 */
object StraightLineSnap {

    /** Snap when the raw angle is within this many degrees of a 45° multiple. */
    const val SNAP_IN_DEG = 2f

    /** Once snapped, stay snapped until the raw angle deviates by at least this many degrees. */
    const val SNAP_OUT_DEG = 5f

    private const val STEP_DEG = 45f

    /**
     * The snapped angle after a pen move to raw direction [rawAngleDeg] (degrees, any range),
     * given the [currentSnapped] angle (null = free). Returns a multiple of 45° in (-180, 180],
     * or null when free.
     */
    fun updateSnap(currentSnapped: Float?, rawAngleDeg: Float): Float? {
        if (currentSnapped != null && abs(angleDelta(rawAngleDeg, currentSnapped)) < SNAP_OUT_DEG) {
            return currentSnapped // hysteresis: keep the snap until the pen clearly breaks away
        }
        val nearest = (rawAngleDeg / STEP_DEG).roundToInt() * STEP_DEG
        return if (abs(angleDelta(rawAngleDeg, nearest)) <= SNAP_IN_DEG) normalize(nearest) else null
    }

    /**
     * The raw endpoint ([x2], [y2]) projected onto the snapped direction [angleDeg] from the
     * anchor ([x1], [y1]) — the along-axis component of the drag, so movement along the snapped
     * line still tracks the pen exactly.
     */
    fun projectEndpoint(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        angleDeg: Float,
    ): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val ux = cos(rad).toFloat()
        val uy = sin(rad).toFloat()
        val along = (x2 - x1) * ux + (y2 - y1) * uy
        return (x1 + along * ux) to (y1 + along * uy)
    }

    /** Signed shortest angular difference a − b, wrapped to (-180, 180]. */
    private fun angleDelta(a: Float, b: Float): Float = normalize(a - b)

    private fun normalize(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }
}
