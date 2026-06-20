package ai.elrond.ui.theme

import ai.elrond.R
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight

/**
 * Leap AI typography (Brand Guidelines V1.0): **Poppins** ExtraBold/Bold for display + headlines,
 * **Albert Sans** for titles / body / UI. Bundled OFL `.ttf` in `res/font` (full glyph coverage — the
 * handoff's `.woff2` are subset to just the mockup's characters, so unusable for real text).
 *
 * The AI "handwritten" style is intentionally separate and unaffected: it uses
 * [ai.elrond.ui.HandwritingFontFamily] (Caveat) applied directly at its call sites, never the
 * Material [Typography] below.
 */

// Poppins ships as static weights (700/800).
private val Poppins = FontFamily(
    Font(R.font.poppins_bold, FontWeight.Bold),
    Font(R.font.poppins_extrabold, FontWeight.ExtraBold),
)

// Albert Sans is a single variable font (weight axis); pin the weights the UI uses.
@OptIn(ExperimentalTextApi::class)
private val AlbertSans = FontFamily(
    Font(R.font.albert_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.albert_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.albert_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.albert_sans, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

/** Material3 type scale with Leap families swapped in (Poppins for display/headline, Albert Sans elsewhere). */
val LeapTypography: Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.ExtraBold),
        displayMedium = displayMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.ExtraBold),
        displaySmall = displaySmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        headlineLarge = headlineLarge.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        headlineMedium = headlineMedium.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontFamily = Poppins, fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontFamily = AlbertSans),
        titleMedium = titleMedium.copy(fontFamily = AlbertSans),
        titleSmall = titleSmall.copy(fontFamily = AlbertSans),
        bodyLarge = bodyLarge.copy(fontFamily = AlbertSans),
        bodyMedium = bodyMedium.copy(fontFamily = AlbertSans),
        bodySmall = bodySmall.copy(fontFamily = AlbertSans),
        labelLarge = labelLarge.copy(fontFamily = AlbertSans),
        labelMedium = labelMedium.copy(fontFamily = AlbertSans),
        labelSmall = labelSmall.copy(fontFamily = AlbertSans),
    )
}
