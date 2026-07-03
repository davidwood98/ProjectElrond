package ai.elrond.ui

import ai.elrond.presentation.CanvasViewModel
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Paints the in-progress dashed/dotted stroke while the pen is down (FA-23). The wet ink
 * layer renders whatever the brush paints — it can't dash — so InkCanvas buffers a non-solid
 * stroke's points in the ViewModel and this overlay draws them in the actual line style
 * (the same [ai.elrond.domain.InkLineType] pattern spec the baked segments will use).
 *
 * The state is read INSIDE the draw lambda (the FA-10 rule: a state read in a draw scope
 * invalidates the draw phase on every change — a captured value would freeze).
 */
@Composable
internal fun LivePatternStrokeOverlay(viewModel: CanvasViewModel, modifier: Modifier = Modifier) {
    val live by viewModel.livePatternStroke.collectAsStateWithLifecycle()
    val straight by viewModel.straightLinePreview.collectAsStateWithLifecycle()
    val page by viewModel.pageTransform.collectAsStateWithLifecycle()
    Canvas(modifier) {
        val t = page

        // Hold-to-straighten preview (FA-23): the snapped line, endpoint following the pen.
        straight?.let { line ->
            drawLine(
                color = Color(line.spec.colorArgb),
                start = Offset(t.pageToScreenX(line.x1), t.pageToScreenY(line.y1)),
                end = Offset(t.pageToScreenX(line.x2), t.pageToScreenY(line.y2)),
                strokeWidth = (line.spec.size * t.scale).coerceAtLeast(1f),
                cap = StrokeCap.Round,
                pathEffect = patternEffect(line.lineType, line.spec.size, t.scale),
            )
        }

        val stroke = live ?: return@Canvas
        if (stroke.points.isEmpty()) return@Canvas
        val width = (stroke.spec.size * t.scale).coerceAtLeast(1f)
        val color = Color(stroke.spec.colorArgb)
        if (stroke.points.size == 1) {
            val p = stroke.points.first()
            drawCircle(color, radius = width / 2f, center = Offset(t.pageToScreenX(p.x), t.pageToScreenY(p.y)))
            return@Canvas
        }
        val path = Path()
        stroke.points.forEachIndexed { i, p ->
            val x = t.pageToScreenX(p.x)
            val y = t.pageToScreenY(p.y)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                pathEffect = patternEffect(stroke.lineType, stroke.spec.size, t.scale),
            ),
        )
    }
}

/** The [type]'s dash effect in screen pixels ([brushSize] page units × [scale]); null for solid. */
private fun patternEffect(
    type: ai.elrond.domain.InkLineType,
    brushSize: Float,
    scale: Float,
): PathEffect? {
    val intervals = type.dashIntervals(brushSize)
    if (intervals.isEmpty()) return null
    return PathEffect.dashPathEffect(FloatArray(intervals.size) { i -> intervals[i] * scale })
}
