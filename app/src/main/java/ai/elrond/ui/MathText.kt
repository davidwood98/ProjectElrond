package ai.elrond.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/**
 * Renders simple maths (arithmetic, fractions, exponents, basic calculus symbols)
 * legibly: `a/b` becomes a stacked fraction, `x^2` becomes a superscript. Anything
 * it doesn't recognise falls back to plain inline text. Not a full LaTeX engine —
 * scoped to the POC's "basic arithmetic, fractions, simple algebra, simple
 * differentiation & integration" requirement.
 */
@Composable
fun MathText(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    // Render line by line; within a line lay tokens out in a Row so fractions stack.
    Column(modifier = modifier) {
        text.split("\n").forEach { line ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                line.trim().split(Regex("\\s+")).forEach { token ->
                    if (token.isEmpty()) return@forEach
                    if (isFraction(token)) {
                        val (num, den) = token.split("/", limit = 2)
                        Fraction(num, den, color, fontFamily, fontSize)
                    } else {
                        Text(
                            text = inline(token),
                            color = color,
                            fontFamily = fontFamily,
                            fontSize = fontSize,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun isFraction(token: String): Boolean {
    val parts = token.split("/")
    return parts.size == 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty() &&
        parts[0].first().isLetterOrDigit() && parts[1].first().isLetterOrDigit()
}

/** Builds an inline string converting `base^exp` into a superscripted exponent. */
private fun inline(token: String): AnnotatedString = buildAnnotatedString {
    val caret = token.indexOf('^')
    if (caret <= 0 || caret == token.lastIndex) {
        append(token)
        return@buildAnnotatedString
    }
    append(token.substring(0, caret))
    withStyle(SpanStyle(baselineShift = BaselineShift.Superscript)) {
        append(token.substring(caret + 1))
    }
}

@Composable
private fun Fraction(
    numerator: String,
    denominator: String,
    color: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = 3.dp),
    ) {
        Text(inline(numerator), color = color, fontFamily = fontFamily, fontSize = fontSize)
        HorizontalDivider(
            color = color,
            thickness = 1.dp,
            modifier = Modifier.width(((maxOf(numerator.length, denominator.length)) * 9).dp),
        )
        Text(inline(denominator), color = color, fontFamily = fontFamily, fontSize = fontSize)
    }
}
