package ai.elrond.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Design tokens from the Claude Design handoff that Material3's `ColorScheme`/`Shapes` don't capture:
 * the note-toolbar surfaces, the selected-tool accent and its two derived shades, the hairline
 * border, and the tile/container radii.
 *
 * Centralising them here is what makes a future Claude Design handoff a *one-place* edit — change a
 * token in [ElrondTheme] and every toolbar/button picks it up. Read them in composables via
 * [LeapTheme.tokens].
 */
data class LeapTokens(
    /** Selected-state accent (Leap Blue by default). The handoff's `--acc`. */
    val accent: Color = LeapBlue,
    /** White toolbar container surface. */
    val toolbarSurface: Color = LeapWhite,
    /** Hairline toolbar/container border. */
    val toolbarBorder: Color = Neutral200,
    /** Resting tool-icon colour (Leap Grey). */
    val iconDefault: Color = LeapGrey,
    /** Disabled action colour (Neutral 300) — e.g. redo when nothing to redo. */
    val iconDisabled: Color = Neutral300,
    /** Pressed/hover tile background. */
    val tilePressed: Color = Neutral100,
    /** Button-tile corner radius. */
    val tileRadius: Dp = 12.dp,
    /** Toolbar-container corner radius. */
    val containerRadius: Dp = 16.dp,
) {
    /** A · soft-tile background — the handoff's `--acc-soft` = `color-mix(srgb, accent 16%, #fff)`. */
    val accentSoft: Color get() = mixSrgb(accent, LeapWhite, 0.16f)

    /**
     * A · soft-tile icon + C · underline icon — the handoff's `--acc-strong`
     * = `color-mix(srgb, accent 74%, #11181c)`.
     */
    val accentStrong: Color get() = mixSrgb(accent, ACCENT_STRONG_MIX, 0.74f)

    companion object {
        /** The near-black the accent is mixed toward for `--acc-strong`. */
        val ACCENT_STRONG_MIX = Color(0xFF11181C)
    }
}

/**
 * `color-mix(in srgb, [a] [fractionOfA]%, [b])` — a component-wise blend of two colours in the
 * gamma-encoded sRGB space, matching CSS `color-mix(in srgb, …)` (which the handoff uses to derive
 * the accent shades). [fractionOfA] is the weight of [a] in 0..1.
 */
fun mixSrgb(a: Color, b: Color, fractionOfA: Float): Color {
    val t = fractionOfA.coerceIn(0f, 1f)
    val u = 1f - t
    return Color(
        red = a.red * t + b.red * u,
        green = a.green * t + b.green * u,
        blue = a.blue * t + b.blue * u,
        alpha = a.alpha * t + b.alpha * u,
    )
}

/** Provides [LeapTokens] down the tree. Defaults to the brand tokens so reads never crash. */
val LocalLeapTokens = staticCompositionLocalOf { LeapTokens() }

/** Accessor for the current [LeapTokens] — mirrors `MaterialTheme.colorScheme`. */
object LeapTheme {
    val tokens: LeapTokens
        @Composable @ReadOnlyComposable get() = LocalLeapTokens.current
}
