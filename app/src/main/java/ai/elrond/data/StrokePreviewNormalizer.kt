package ai.elrond.data

import kotlin.math.ceil

/**
 * Normalizes stroke polylines into the 0..1 range (aspect-preserving) and
 * downsamples points so note-card thumbnails are cheap to draw. Pure logic.
 */
object StrokePreviewNormalizer {

    fun normalize(
        polylines: List<List<Pair<Float, Float>>>,
        maxPointsPerStroke: Int = MAX_POINTS_PER_STROKE,
    ): List<List<Pair<Float, Float>>> {
        val allPoints = polylines.flatten()
        if (allPoints.isEmpty()) return emptyList()

        val minX = allPoints.minOf { it.first }
        val minY = allPoints.minOf { it.second }
        val maxX = allPoints.maxOf { it.first }
        val maxY = allPoints.maxOf { it.second }
        // Single scale for both axes preserves the handwriting's aspect ratio.
        val span = maxOf(maxX - minX, maxY - minY).coerceAtLeast(1f)

        return polylines
            .filter { it.isNotEmpty() }
            .map { line ->
                downsample(line, maxPointsPerStroke).map { (x, y) ->
                    (x - minX) / span to (y - minY) / span
                }
            }
    }

    private fun downsample(line: List<Pair<Float, Float>>, max: Int): List<Pair<Float, Float>> {
        if (line.size <= max) return line
        val stride = ceil(line.size / max.toFloat()).toInt()
        return line.filterIndexed { i, _ -> i % stride == 0 || i == line.lastIndex }
    }

    private const val MAX_POINTS_PER_STROKE = 48
}
