package ai.elrond.ui

import ai.elrond.domain.LiveTransform
import ai.elrond.domain.NotebookLink
import ai.elrond.domain.PageTransform
import ai.elrond.domain.safeScale
import ai.elrond.ui.theme.LeapTheme
import ai.elrond.ui.theme.Neutral500
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * An on-canvas link box referencing another notebook (FA-24) — a compact link-style chip showing
 * the target's cached title. A **separate composable from [AiInkNoteView]** (two conceptually
 * different note kinds share the passive-render *technique*, not the view), but the same rules
 * apply:
 *
 * - **Passive** — no pointer input; the pen writes straight over it. Tap-to-open and
 *   hold-to-select are hit-tested by InkCanvas via `CanvasViewModel.linkAt`; selection chrome is
 *   drawn separately.
 * - Anchored in page space, with the page zoom + any live selection move/scale applied as a
 *   `graphicsLayer` about the top-left (the FA-10 rule — never recomputed inside a draw lambda).
 * - Reports its measured page-space size via [onMeasured] so the selection box hugs it.
 *
 * A **broken** link (target deleted) renders greyed as "Reference not found" — visually dead per
 * the FA-5 dead-link rule; InkCanvas's tap path no-ops on it, but press-and-hold still opens the
 * Redefine/Delete menu.
 */
@Composable
fun NotebookLinkView(
    link: NotebookLink,
    transform: PageTransform,
    modifier: Modifier = Modifier,
    liveTransform: LiveTransform = LiveTransform.IDENTITY,
    /**
     * The target notebook's CURRENT title, when known (FA-24 device feedback: the label must
     * track renames). [NotebookLink.linkText] is only the at-link-time cache, used as the
     * fallback while the live title hasn't loaded.
     */
    liveTitle: String? = null,
    onMeasured: (widthPx: Float, heightPx: Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val scale = transform.safeScale
    val maxWidthDp = with(density) { link.widthPx.toDp() }
    val minWidthDp = with(density) { NotebookLink.MIN_WIDTH_PX.toDp() }
    val heightModifier = link.heightPx
        ?.let { h -> Modifier.height(with(density) { h.toDp() }) }
        ?: Modifier
    val accent = LeapTheme.tokens.accent
    val contentColor = if (link.isBroken) Neutral500 else accent
    val label = if (link.isBroken) "Reference not found" else liveTitle ?: link.linkText

    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    transform.pageToScreenX(liveTransform.applyX(link.x)).roundToInt(),
                    transform.pageToScreenY(liveTransform.applyY(link.y)).roundToInt(),
                )
            }
            .graphicsLayer {
                scaleX = scale * liveTransform.scaleX
                scaleY = scale * liveTransform.scaleY
                transformOrigin = TransformOrigin(0f, 0f)
            }
            .widthIn(min = minWidthDp, max = maxWidthDp)
            .then(heightModifier)
            .onSizeChanged { onMeasured(it.width.toFloat(), it.height.toFloat()) },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(12.dp))
                .border(1.dp, contentColor.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (link.isBroken) Icons.Outlined.LinkOff else Icons.Outlined.Link,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}
