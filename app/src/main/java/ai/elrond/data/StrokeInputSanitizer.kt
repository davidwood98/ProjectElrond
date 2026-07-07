package ai.elrond.data

import kotlin.math.PI

/**
 * Guarantees a decoded point list is valid for ink reconstruction before it reaches
 * `MutableStrokeInputBatch.add` — ink 1.0.0 removed the skip-invalid `addOrIgnore`, and `add`
 * throws on any point invalid relative to the batch (the FA-7 regression: one early bad point
 * cascades and collapses the reloaded stroke to a dot; see CLAUDE.md "upgrading androidx.ink").
 *
 * Runs at the load boundary only, so it also repairs data persisted by older builds. Pure JVM —
 * no ink natives — so the rules are unit-testable; the actual reconstruction is covered on-device
 * by StrokeSerializationInstrumentedTest's adversarial fixtures.
 */
object StrokeInputSanitizer {

    // Sentinel ink uses for "not reported" (StrokeInput.NO_PRESSURE / NO_TILT / NO_ORIENTATION).
    private const val UNREPORTED = -1f
    private const val MAX_TILT = (PI / 2).toFloat()
    private const val MAX_ORIENTATION = (2 * PI).toFloat()

    fun sanitize(points: List<SerializedStrokeInput>): List<SerializedStrokeInput> {
        val out = ArrayList<SerializedStrokeInput>(points.size)
        var prev: SerializedStrokeInput? = null
        for (p in points) {
            if (!p.x.isFinite() || !p.y.isFinite()) continue
            if (prev != null && p.x == prev.x && p.y == prev.y) continue // coincident consecutive
            val t = if (prev == null) maxOf(p.t, 0L) else maxOf(p.t, prev.t + 1)
            val clean = p.copy(
                t = t,
                pressure = clampChannel(p.pressure, 1f),
                tilt = clampChannel(p.tilt, MAX_TILT),
                orientation = clampChannel(p.orientation, MAX_ORIENTATION),
            )
            out.add(clean)
            prev = clean
        }
        return unifyChannelReporting(out)
    }

    /**
     * A drifted finite value clamps into [0, max] (still reported — pressure data is kept for
     * future use, never discarded over range drift); only non-finite values and the stored -1
     * sentinel itself stay [UNREPORTED].
     */
    private fun clampChannel(value: Float, max: Float): Float = when {
        !value.isFinite() || value == UNREPORTED -> UNREPORTED
        else -> value.coerceIn(0f, max)
    }

    /**
     * Ink 1.0.0 also validates the batch as a whole: "Either all or none of the inputs in a batch
     * must report `pressure`" (likewise tilt/orientation) — caught on-device by the FA-23 merge
     * gate. Per-point sanitising can leave a channel reported on some points and unreported on
     * others; rather than dropping the channel (pressure must survive serialisation for future
     * use), the gaps are FILLED from the nearest reported neighbour. A channel no point reports
     * stays unreported everywhere — already consistent.
     */
    private fun unifyChannelReporting(points: List<SerializedStrokeInput>): List<SerializedStrokeInput> {
        if (points.isEmpty()) return points
        val pressure = repairChannel(FloatArray(points.size) { points[it].pressure })
        val tilt = repairChannel(FloatArray(points.size) { points[it].tilt })
        val orientation = repairChannel(FloatArray(points.size) { points[it].orientation })
        return points.mapIndexed { i, p ->
            if (p.pressure == pressure[i] && p.tilt == tilt[i] && p.orientation == orientation[i]) {
                p
            } else {
                p.copy(pressure = pressure[i], tilt = tilt[i], orientation = orientation[i])
            }
        }
    }

    /** Fills unreported gaps in a mixed channel: forward-fill from the last reported value, then
     *  back-fill any leading gap from the first reported value. All-unreported stays as-is. */
    private fun repairChannel(values: FloatArray): FloatArray {
        if (values.none { it != UNREPORTED }) return values
        var last = UNREPORTED
        for (i in values.indices) {
            if (values[i] != UNREPORTED) last = values[i] else if (last != UNREPORTED) values[i] = last
        }
        for (i in values.indices.reversed()) {
            if (values[i] != UNREPORTED) last = values[i] else values[i] = last
        }
        return values
    }
}
