package ai.elrond.ui

import ai.elrond.R
import ai.elrond.domain.AiInkNote
import ai.elrond.domain.MathDetector
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import ai.elrond.ui.theme.LeapPink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Handwriting-style font for AI ink (Caveat, OFL — see licenses/CAVEAT_OFL.txt). */
val HandwritingFontFamily = FontFamily(Font(R.font.caveat))

/** AI ink colour — Leap Pink, a brand accent kept distinct from the navy user ink and cyan toolbar. */
val AiInkColor = LeapPink

/** Colour for AI error responses and the "request unclear" affordance. */
val ErrorInkColor = Color(0xFFC62828)

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

/**
 * An error-type AI response (e.g. "I need more information, request unclear"). Distinct from a
 * normal answer and independent of the "select on create" setting.
 *
 * It does NOT position itself at the trigger location (which can be off-screen for a `/Q` near a
 * page edge — its Yes/No / Edit / Okay controls must always be reachable); the caller places it,
 * centred on screen. When the model offered a best guess ([AiInkNote.suggestedQuestion]) it first
 * shows a **Did you mean: …?** clarifier with **Yes** / **No**: Yes re-sends the guess and produces
 * a normal answer placed inline at the trigger; No falls back to the **Edit prompt** / **Okay**
 * controls. When there was no guess it shows those controls straight away. "Okay" dismisses it
 * exactly like the ✕ on a normal note. "Edit prompt" reveals an editable, pre-filled copy of the
 * recognized question with a **Re-send** button — also fired by the keyboard's Send/Enter action.
 */
@Composable
fun AiErrorNoteView(
    note: AiInkNote,
    onResend: (String) -> Unit,
    onOkay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember(note.id) { mutableStateOf(false) }
    var declinedSuggestion by remember(note.id) { mutableStateOf(false) }
    val guess = note.suggestedQuestion?.takeIf { it.isNotBlank() }
    // Prefill the editor with the model's fuller suggested prompt when it offered one (that's the
    // "full prompt" the user wants to refine), else the raw recognized question.
    var prompt by remember(note.id) { mutableStateOf(guess ?: note.sourceQuestion.orEmpty()) }
    val focusRequester = remember(note.id) { FocusRequester() }
    val showClarify = guess != null && !declinedSuggestion && !editing

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color.White.copy(alpha = 0.98f),
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, ErrorInkColor.copy(alpha = 0.5f)),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = note.text,
                    fontFamily = HandwritingFontFamily,
                    fontSize = BASE_FONT_SP.sp,
                    lineHeight = (BASE_FONT_SP * 1.25f).sp,
                    color = ErrorInkColor,
                )
                when {
                    showClarify -> {
                        Text(
                            text = "Did you mean:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = guess!!,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            TextButton(onClick = { declinedSuggestion = true }) { Text("No") }
                            FilledTonalButton(onClick = { onResend(guess) }) { Text("Yes") }
                        }
                    }

                    editing -> {
                        OutlinedTextField(
                            value = prompt,
                            onValueChange = { prompt = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp)
                                .focusRequester(focusRequester),
                            label = { Text("Edit prompt") },
                            // Multi-line + wrapping so a long suggested prompt is fully visible and
                            // editable (singleLine clipped it to one horizontally-scrolling line).
                            singleLine = false,
                            minLines = 2,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = { if (prompt.isNotBlank()) onResend(prompt) },
                            ),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            FilledTonalButton(
                                onClick = { if (prompt.isNotBlank()) onResend(prompt) },
                                enabled = prompt.isNotBlank(),
                            ) { Text("Re-send") }
                        }
                        // Focus the field (and raise the keyboard) as soon as editing starts.
                        LaunchedEffect(note.id) { focusRequester.requestFocus() }
                    }

                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        ) {
                            TextButton(onClick = { editing = true }) { Text("Edit prompt") }
                            FilledTonalButton(onClick = onOkay) { Text("Okay") }
                        }
                    }
                }
            }
        }
}
