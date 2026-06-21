package ai.elrond.domain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pure geometry for the lasso (circle) activation gesture — detecting whether a stroke is an
 * intentional closed loop and which other strokes it encloses. JVM-testable; [ai.elrond.ai]
 * stroke adapters (in StrokeLines.kt) feed it real ink points.
 */
object GestureTriggerDetector {

    data class Point(val x: Float, val y: Float)

    /** A loop needs enough samples to not be a stray tap/flick. */
    const val MIN_LOOP_POINTS = 8

    /** Below this bounding-box diagonal a "loop" is too small to be deliberate. */
    const val MIN_DIAGONAL_PX = 24f

    /** The start/end gap must be within this fraction of the bounding diagonal to count as closed. */
    const val CLOSURE_RATIO = 0.35f

    /** Enclosed area must be at least this fraction of diagonal² — rules out thin back-and-forth scribbles. */
    const val MIN_AREA_RATIO = 0.06f

    /**
     * True when [points] form a deliberate lasso: enough points, a closed path (start near
     * end), big enough, and enclosing real 2-D area (not a straight line drawn and retraced).
     */
    fun isLasso(points: List<Point>): Boolean {
        if (points.size < MIN_LOOP_POINTS) return false
        val diagonal = boundingDiagonal(points)
        if (diagonal < MIN_DIAGONAL_PX) return false
        val closureGap = distance(points.first(), points.last())
        if (closureGap > CLOSURE_RATIO * diagonal) return false
        return polygonArea(points) >= MIN_AREA_RATIO * diagonal * diagonal
    }

    /** Even-odd ray-cast point-in-polygon test. */
    fun contains(polygon: List<Point>, p: Point): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            val crossesY = (a.y > p.y) != (b.y > p.y)
            if (crossesY) {
                val dy = (b.y - a.y).let { if (it == 0f) 1e-6f else it }
                val xAtP = (b.x - a.x) * (p.y - a.y) / dy + a.x
                if (p.x < xAtP) inside = !inside
            }
            j = i
        }
        return inside
    }

    /** Indices of the [centroids] that fall inside the lasso [polygon]. */
    fun enclosedIndices(polygon: List<Point>, centroids: List<Point>): List<Int> =
        centroids.indices.filter { contains(polygon, centroids[it]) }

    /** Diagonal of the points' axis-aligned bounding box. */
    fun boundingDiagonal(points: List<Point>): Float {
        if (points.isEmpty()) return 0f
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        points.forEach {
            minX = minOf(minX, it.x); maxX = maxOf(maxX, it.x)
            minY = minOf(minY, it.y); maxY = maxOf(maxY, it.y)
        }
        val dx = maxX - minX
        val dy = maxY - minY
        return sqrt(dx * dx + dy * dy)
    }

    /** Absolute polygon area via the shoelace formula. */
    fun polygonArea(points: List<Point>): Float {
        if (points.size < 3) return 0f
        var sum = 0f
        var j = points.lastIndex
        for (i in points.indices) {
            sum += (points[j].x + points[i].x) * (points[j].y - points[i].y)
            j = i
        }
        return abs(sum) / 2f
    }

    private fun distance(a: Point, b: Point): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return sqrt(dx * dx + dy * dy)
    }
}
