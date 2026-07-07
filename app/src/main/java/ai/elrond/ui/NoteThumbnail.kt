package ai.elrond.ui

import ai.elrond.domain.NotePage
import ai.elrond.domain.StrokePreview
import ai.elrond.presentation.CanvasViewModel
import ai.elrond.presentation.NoteListViewModel
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag

/**
 * Shared note-card thumbnail (used by the Library grid and the legacy list). Shows the cached WebP
 * if one exists (decoded off the main thread), otherwise draws the stroke polylines (also decoded
 * off-main — see [NoteListViewModel.preview]). Both are re-fetched when the page is edited (keyed on
 * [NotePage.modifiedAt]), so a freshly generated thumbnail replaces the polyline fallback next visit.
 */
@Composable
fun NoteThumbnail(
    page: NotePage,
    viewModel: NoteListViewModel,
    modifier: Modifier = Modifier,
) {
    NoteThumbnail(pageId = page.id, modifiedAt = page.modifiedAt, viewModel = viewModel, modifier = modifier)
}

/** Thumbnail by page id + modifiedAt (FA-20) — for notebook cards / the page index that hold a cover
 *  page id rather than a full [NotePage]. */
@Composable
fun NoteThumbnail(
    pageId: String,
    modifiedAt: Long,
    viewModel: NoteListViewModel,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<Bitmap?>(null, pageId, modifiedAt) {
        value = viewModel.thumbnail(pageId)
    }
    val cached = bitmap
    if (cached != null) {
        Image(
            bitmap = cached.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.testTag(THUMBNAIL_IMAGE_TAG),
        )
    } else {
        val preview by produceState<List<StrokePreview>>(emptyList(), pageId, modifiedAt) {
            value = viewModel.preview(pageId)
        }
        StrokeThumbnail(previews = preview, modifier = modifier.testTag(THUMBNAIL_FALLBACK_TAG))
    }
}

/** Miniature of the page's ink, drawn from normalized (0..1) per-stroke previews (FA-23: each in
 *  its own colour; highlighter marks wide + translucent). */
@Composable
fun StrokeThumbnail(
    previews: List<StrokePreview>,
    modifier: Modifier = Modifier,
) {
    val inkColor = Color(CanvasViewModel.USER_INK_COLOR)
    Canvas(modifier = modifier) {
        if (previews.isEmpty()) {
            // Blank page hint: a few faint ruled lines.
            val lineColor = inkColor.copy(alpha = 0.08f)
            for (i in 1..3) {
                val y = size.height * i / 4f
                drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            }
            return@Canvas
        }
        val scale = minOf(size.width, size.height) * 0.9f
        val pad = minOf(size.width, size.height) * 0.05f
        previews.forEach { stroke ->
            if (stroke.points.isEmpty()) return@forEach
            // Highlighter translucency is baked into the colour; plain ink gets the card alpha.
            val color = Color(stroke.colorArgb).let { if (stroke.isHighlighter) it else it.copy(alpha = 0.85f) }
            val width = if (stroke.isHighlighter) 10f else 2.5f
            if (stroke.points.size == 1) {
                val (x, y) = stroke.points.first()
                drawCircle(color, radius = width / 2f, center = Offset(pad + x * scale, pad + y * scale))
                return@forEach
            }
            val path = Path()
            stroke.points.forEachIndexed { i, (x, y) ->
                val px = pad + x * scale
                val py = pad + y * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
        }
    }
}

/** Test tags distinguishing the cached-bitmap thumbnail from the polyline fallback. */
internal const val THUMBNAIL_IMAGE_TAG = "note-thumbnail-image"
internal const val THUMBNAIL_FALLBACK_TAG = "note-thumbnail-fallback"
