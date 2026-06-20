package ai.elrond.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
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
 */
private val LeapColorScheme = lightColorScheme(
    primary = LeapBlue,
    onPrimary = LeapBlack, // handoff: foreground on the accent fill is Leap Black
    primaryContainer = Blue100,
    onPrimaryContainer = Blue700,
    secondary = LeapNavy,
    onSecondary = LeapWhite,
    secondaryContainer = Blue100, // selected nav/chip tint reads as a soft Leap-blue
    onSecondaryContainer = Blue700,
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
fun ElrondTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLeapTokens provides LeapTokens()) {
        MaterialTheme(
            colorScheme = LeapColorScheme,
            shapes = LeapShapes,
            typography = LeapTypography,
            content = content,
        )
    }
}
