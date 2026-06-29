package ai.elrond.ui

import ai.elrond.R
import ai.elrond.domain.AiInkNote
import ai.elrond.domain.LiveTransform
import ai.elrond.domain.MathDetector
import ai.elrond.domain.PageTransform
import ai.elrond.domain.safeScale
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import ai.elrond.ui.theme.LeapPink
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
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
 * An AI response rendered as handwriting-style ink on the canvas (FA-21).
 *
 * The box is **passive** — it never captures pointer input, so the pen draws straight through onto
 * the canvas (you can write over an AI answer). Selecting it (a 1.5s press-and-hold on the canvas,
 * or a lasso) is handled by [CanvasViewModel]; the selection overlay draws the box + handles +
 * toolbar — this view only renders the text.
 *
 * It is **content-hugging**: the box sizes to its text up to the note's full-line width cap, so a
 * short answer is tight and a long one wraps. Width, height and font all scale with the page zoom
 * ([transform]) like the grid, and with the note's own [AiInkNote.fontScale] (baked in by a
 * ratio-locked resize). While the note moves/scales as part of a selection, [liveTransform] previews
 * that move/scale — applied as a layer modifier, never recomputed in a draw lambda (the FA-10 rule).
 * [onMeasured] reports the box's page-space size so the ViewModel can hug the selection box around
 * it. Mathematical answers render via [MathText].
 */
@Composable
fun AiInkNoteView(
    note: AiInkNote,
    transform: PageTransform,
    modifier: Modifier = Modifier,
    liveTransform: LiveTransform = LiveTransform.IDENTITY,
    onMeasured: (widthPx: Float, heightPx: Float) -> Unit = { _, _ -> },
) {
    val density = LocalDensity.current
    val scale = transform.safeScale
    val maxWidthDp = with(density) { (note.widthPx * scale).toDp() }
    val minWidthDp = with(density) { (AiInkNote.MIN_WIDTH_PX * scale).toDp() }
    val heightModifier = note.heightPx
        ?.let { h -> Modifier.height(with(density) { (h * scale).toDp() }) }
        ?: Modifier
    val fontSize = (BASE_FONT_SP * note.fontScale * scale).sp
    val isMath = MathDetector.isMath(note.text)

    Box(
        modifier = modifier
            // Anchor in page space (with the live move/scale preview applied first), then page → screen.
            .offset {
                IntOffset(
                    transform.pageToScreenX(liveTransform.applyX(note.x)).roundToInt(),
                    transform.pageToScreenY(liveTransform.applyY(note.y)).roundToInt(),
                )
            }
            // Live scale (preview only) about the top-left — matches the bake in transformAiNote.
            .graphicsLayer {
                scaleX = liveTransform.scaleX
                scaleY = liveTransform.scaleY
                transformOrigin = TransformOrigin(0f, 0f)
            }
            // Content-hugging up to the full-line cap; both bounds scale with the page zoom.
            .widthIn(min = minWidthDp, max = maxWidthDp)
            .then(heightModifier)
            // Report the box's page-space size so the selection box hugs it (divide out the zoom).
            .onSizeChanged { onMeasured(it.width / scale, it.height / scale) },
    ) {
        val content = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        if (isMath) {
            MathText(
                text = note.text,
                color = AiInkColor,
                fontFamily = HandwritingFontFamily,
                fontSize = fontSize,
                modifier = content,
            )
        } else {
            Text(
                text = note.text,
                fontFamily = HandwritingFontFamily,
                fontSize = fontSize,
                lineHeight = fontSize * 1.25f,
                color = AiInkColor,
                modifier = content,
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
