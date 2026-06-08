package ai.elrond.ui

import ai.elrond.R
import ai.elrond.ai.AiInkNote
import ai.elrond.ai.MathDetector
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Handwriting-style font for AI ink (Caveat, OFL — see licenses/CAVEAT_OFL.txt). */
val HandwritingFontFamily = FontFamily(Font(R.font.caveat))

/** AI ink colour — a distinct but aesthetically consistent violet. */
val AiInkColor = Color(0xFF6A1B9A)

/**
 * An AI response rendered as handwriting-style ink on the canvas.
 *
 * When [selected] it shows a border, a remove ✕ and a resize handle, and can be
 * dragged/resized. When deselected it's just ink in the flow of the notes — a
 * long-press re-selects it, and tapping anywhere off the box deselects it. The AI
 * colour alone distinguishes it from user ink. Mathematical answers render via [MathText].
 */
@Composable
fun AiInkNoteView(
    note: AiInkNote,
    selected: Boolean,
    onSelect: () -> Unit,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (dWidth: Float, dHeight: Float) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { note.widthPx.toDp() }
    val heightModifier = note.heightPx
        ?.let { h -> Modifier.height(with(density) { h.toDp() }) }
        ?: Modifier

    val isMath = MathDetector.isMath(note.text)

    Box(
        modifier = modifier
            .offset { IntOffset(note.x.roundToInt(), note.y.roundToInt()) }
            .width(widthDp)
            .then(heightModifier)
            .then(
                if (selected) {
                    Modifier
                        .shadow(6.dp, RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .border(1.dp, AiInkColor.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                } else {
                    Modifier
                },
            )
            .pointerInput(note.id, selected) {
                // Long-press re-selects a placed note; deselecting happens by tapping
                // off the box (handled by the canvas), not by tapping inside it.
                detectTapGestures(
                    onLongPress = { if (!selected) onSelect() },
                )
            }
            .then(
                if (selected) {
                    Modifier.pointerInput(note.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onMove(drag.x, drag.y)
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val contentPadding = Modifier.padding(
            start = if (selected) 22.dp else 4.dp,
            top = if (selected) 6.dp else 2.dp,
            end = if (selected) 28.dp else 4.dp,
            bottom = if (selected) 18.dp else 2.dp,
        )
        if (isMath) {
            MathText(
                text = note.text,
                color = AiInkColor,
                fontFamily = HandwritingFontFamily,
                fontSize = BASE_FONT_SP.sp,
                modifier = contentPadding,
            )
        } else {
            Text(
                text = note.text,
                fontFamily = HandwritingFontFamily,
                fontSize = BASE_FONT_SP.sp,
                lineHeight = (BASE_FONT_SP * 1.25f).sp,
                color = AiInkColor,
                maxLines = Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
                modifier = contentPadding,
            )
        }

        if (selected) {
            Text(
                text = "✕",
                fontSize = 14.sp,
                color = AiInkColor.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove)
                    .padding(4.dp),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(18.dp)
                    .background(AiInkColor.copy(alpha = 0.3f), CircleShape)
                    .pointerInput(note.id) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onResize(drag.x, drag.y) // dx → width, dy → height
                        }
                    },
            )
        }
    }
}

private const val BASE_FONT_SP = 22
