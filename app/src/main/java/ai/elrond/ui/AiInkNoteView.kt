package ai.elrond.ui

import ai.elrond.R
import ai.elrond.ai.AiInkNote
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Handwriting-style font for AI ink (Caveat, OFL — see licenses/CAVEAT_OFL.txt). */
val HandwritingFontFamily = FontFamily(Font(R.font.caveat))

/** AI ink colour — related to, but visually distinct from, the user's navy ink. */
val AiInkColor = Color(0xFF6A1B9A)

/**
 * An AI response rendered as handwriting-style ink on the canvas.
 * Drag the text to move it; drag the corner handle to resize (width and height
 * are independent — aspect ratio is not locked); tap ✕ to remove.
 */
@Composable
fun AiInkNoteView(
    note: AiInkNote,
    onMove: (dx: Float, dy: Float) -> Unit,
    onResize: (dWidth: Float, dHeight: Float) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val widthDp = with(density) { note.widthPx.toDp() }
    val heightModifier = note.heightPx?.let { h ->
        Modifier.height(with(density) { h.toDp() })
    } ?: Modifier

    Box(
        modifier = modifier
            .offset { IntOffset(note.x.roundToInt(), note.y.roundToInt()) }
            .width(widthDp)
            .then(heightModifier)
            .pointerInput(note.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onMove(drag.x, drag.y)
                }
            },
    ) {
        Text(
            text = note.text,
            fontFamily = HandwritingFontFamily,
            fontSize = BASE_FONT_SP.sp,
            lineHeight = (BASE_FONT_SP * 1.25f).sp,
            color = AiInkColor,
            modifier = Modifier.padding(end = 28.dp, bottom = 18.dp),
        )
        Text(
            text = "✕",
            fontSize = 14.sp,
            color = AiInkColor.copy(alpha = 0.5f),
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
                .background(AiInkColor.copy(alpha = 0.25f), CircleShape)
                .pointerInput(note.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        onResize(drag.x, drag.y) // dx → width, dy → height
                    }
                },
        )
    }
}

private const val BASE_FONT_SP = 22
