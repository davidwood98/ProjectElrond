package ai.elrond.domain

/**
 * Highlighter colours (FA-23), carrying the flat low-opacity alpha in the ARGB value so the
 * brush needs no separate opacity channel. How ink's highlighter family composites this alpha
 * is a device-tune item — these constants are the only knob. Device-retuned 2026-07-07: the
 * original Material-palette hues read too pastel over white paper; these are fluorescent
 * (fully-saturated, marker-like) equivalents.
 */
enum class HighlighterColor(val argb: Int) {
    PINK(0x59FF2D95),
    BLUE(0x5900B2FF),
    GREEN(0x5939FF14),
    YELLOW(0x59FDFF00),
    ORANGE(0x59FF9500);

    companion object {
        val DEFAULT = YELLOW

        fun fromName(name: String?): HighlighterColor = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
