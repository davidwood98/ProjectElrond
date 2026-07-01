package ai.elrond.domain

import kotlin.math.hypot

/**
 * Pure point-decimation used by the debug "stroke simplification" knob (perf tuning): given a
 * stroke's input points in page space and a minimum spacing, returns the indices to KEEP.
 *
 * A point is kept only once it is at least [minSpacing] page-units from the last kept point, which
 * thins dense high-rate capture (handwriting routinely lands ~100+ points per stroke) so each
 * stroke's ink mesh is cheaper to build and redraw. The first and last points are always kept so a
 * stroke never loses its endpoints. [minSpacing] <= 0 keeps everything (simplification off).
 *
 * Pure + Compose/ink-free so it is unit-testable; the ink-native apply that rebuilds the [androidx
 * .ink.strokes.Stroke] from the kept points lives in [StrokeTransforms.simplify].
 */
object StrokeSimplifier {

    /** Indices of [points] to keep at the given [minSpacing]; always the full set when off. */
    fun keptIndices(points: List<Pair<Float, Float>>, minSpacing: Float): List<Int> {
        if (minSpacing <= 0f || points.size <= 2) return points.indices.toList()
        val kept = ArrayList<Int>(points.size)
        kept.add(0)
        var (lastX, lastY) = points[0]
        // Walk the interior; keep a point once it is far enough from the last kept one. The final
        // point is forced in below so the stroke's end is never dropped.
        for (i in 1 until points.size - 1) {
            val (x, y) = points[i]
            if (hypot(x - lastX, y - lastY) >= minSpacing) {
                kept.add(i)
                lastX = x
                lastY = y
            }
        }
        kept.add(points.size - 1)
        return kept
    }
}
