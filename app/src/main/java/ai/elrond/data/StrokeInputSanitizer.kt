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
                pressure = if (p.pressure in 0f..1f) p.pressure else UNREPORTED,
                tilt = if (p.tilt in 0f..MAX_TILT) p.tilt else UNREPORTED,
                orientation = if (p.orientation in 0f..MAX_ORIENTATION) p.orientation else UNREPORTED,
            )
            out.add(clean)
            prev = clean
        }
        return out
    }
}
