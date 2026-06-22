package ai.elrond.ui.theme

import ai.elrond.domain.AppAccent
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * App theme, built from the Leap AI design system (Claude Design handoff). A light Material3
 * [androidx.compose.material3.ColorScheme] mapped from the Leap palette ([Color.kt]) + Leap
 * typography ([Type.kt]) + brand radii, with the bespoke design tokens ([LeapTokens]) provided
 * alongside.
 *
 * This is the reusable design-system layer: a future handoff that tweaks the brand changes the
 * palette/type/tokens here and the whole app — main menu, toolbar, settings — follows. Wrap the app
 * content in this (see `MainActivity`).
 *
 * The [AppAccent] is user-selectable (FA-14): the chosen accent flows into both the Material
 * `primary`/`primaryContainer` roles and the [LeapTokens] accent, so a single setting recolours the
 * toolbar selection, the FAB, and every active state app-wide.
 */

/** Maps the Compose-free [AppAccent] enum to its brand [Color]. Lives here so the enum stays pure. */
val AppAccent.color: Color
    get() = when (this) {
        AppAccent.BLUE -> LeapBlue
        AppAccent.NAVY -> LeapNavy
        AppAccent.GREEN -> LeapGreen
        AppAccent.PINK -> LeapPink
    }

/** Readable foreground on a solid accent fill: black on light accents (Blue), white on dark ones. */
internal fun onAccentColor(accent: Color): Color =
    if (accent.luminance() > 0.5f) LeapBlack else LeapWhite

/**
 * Builds the light Material3 scheme for a given accent. The accent-tinted roles use the same
 * `color-mix` shades as [LeapTokens] (`acc-soft` 16% / `acc-strong` 74%) so any of the four accents
 * reads consistently; everything else stays on the Leap neutrals + secondary navy / tertiary pink.
 */
private fun leapColorScheme(accent: Color) = lightColorScheme(
    primary = accent,
    onPrimary = onAccentColor(accent), // handoff: foreground on the accent fill
    primaryContainer = mixSrgb(accent, LeapWhite, 0.16f), // = acc-soft
    onPrimaryContainer = mixSrgb(accent, LeapTokens.ACCENT_STRONG_MIX, 0.74f), // = acc-strong
    secondary = LeapNavy,
    onSecondary = LeapWhite,
    secondaryContainer = mixSrgb(accent, LeapWhite, 0.16f), // selected nav/chip tint = soft accent
    onSecondaryContainer = mixSrgb(accent, LeapTokens.ACCENT_STRONG_MIX, 0.74f),
    tertiary = LeapPink,
    onTertiary = LeapWhite,
    background = LeapWhite,
    onBackground = LeapBlack,
    surface = LeapWhite,
    onSurface = LeapBlack,
    surfaceVariant = Neutral100,
    onSurfaceVariant = LeapGrey,
    // Surface-container ramp (white → light neutral). These back the NavigationBar (bottom bar) and
    // ModalDrawerSheet (side menu); left unset they fall back to the baseline Material *purple*.
    surfaceContainerLowest = LeapWhite,
    surfaceContainerLow = Neutral50,
    surfaceContainer = Neutral100,
    surfaceContainerHigh = Neutral100,
    surfaceContainerHighest = Neutral200,
    inverseSurface = Neutral900, // the on-canvas transient pill — a dark neutral, not purple
    inverseOnSurface = Neutral50,
    surfaceTint = Color.Transparent, // flat brand surfaces — depth comes from shadow, not a tonal tint
    outline = Neutral200,
    outlineVariant = Neutral200,
    error = LeapDanger,
    onError = LeapWhite,
)

/** Brand radii (handoff: controls ~12px, cards ~16px). */
private val LeapShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun ElrondTheme(
    accent: AppAccent = AppAccent.DEFAULT,
    content: @Composable () -> Unit,
) {
    val accentColor = accent.color
    val colorScheme = remember(accentColor) { leapColorScheme(accentColor) }
    CompositionLocalProvider(LocalLeapTokens provides LeapTokens(accent = accentColor)) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = LeapShapes,
            typography = LeapTypography,
            content = content,
        )
    }
}
