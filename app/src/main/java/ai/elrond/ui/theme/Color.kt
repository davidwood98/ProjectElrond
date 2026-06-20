package ai.elrond.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Leap AI brand palette — canonical web hex from the Brand Guidelines V1.0 (2023), as shipped in the
 * Claude Design handoff (`_ds/.../tokens/colors.css`). This is the single source of truth for brand
 * colour; keep it in sync with any future design-system handoff and reference these — never raw hex —
 * from the theme + components.
 */

// ---- Brand colours ----
val LeapGrey = Color(0xFF4F5253) // primary — logo + headlines + resting tool icons
val LeapBlue = Color(0xFF52C6DA) // primary — logo accent + the single "pop" / active accent
val LeapNavy = Color(0xFF4652A3) // secondary
val LeapGreen = Color(0xFF3CB078) // secondary — also success
val LeapPink = Color(0xFFB5579D) // secondary — gradient + highlight
val LeapBlack = Color(0xFF262626) // body text + on-accent foreground

// ---- Blue ramp (tints/shades of Leap Blue, for UI states) ----
val Blue700 = Color(0xFF2A9BAD)
val Blue600 = Color(0xFF3FB4C7)
val Blue300 = Color(0xFF8DD8E5)
val Blue200 = Color(0xFFBCE8EF)
val Blue100 = Color(0xFFE4F6F9)

// ---- Neutral ramp (tints of Leap Black toward white) ----
val Neutral900 = Color(0xFF262626) // = Leap Black
val Neutral800 = Color(0xFF3A3A3A)
val Neutral700 = Color(0xFF4F5253) // = Leap Grey
val Neutral600 = Color(0xFF6B6E6F)
val Neutral500 = Color(0xFF8C8F90)
val Neutral400 = Color(0xFFA9ABAC)
val Neutral300 = Color(0xFFC9CBCC)
val Neutral200 = Color(0xFFE4E5E6)
val Neutral100 = Color(0xFFF2F3F3)
val Neutral50 = Color(0xFFF7F8F8)
val LeapWhite = Color(0xFFFFFFFF)

// ---- Functional ----
val LeapDanger = Color(0xFFD9534F)
