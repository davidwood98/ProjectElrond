package ai.elrond.domain

/**
 * Highlighter colours (FA-23), carrying the flat low-opacity alpha in the ARGB value so the
 * brush needs no separate opacity channel. How ink's highlighter family composites this alpha
 * is a device-tune item — these constants are the only knob.
 */
enum class HighlighterColor(val argb: Int) {
    PINK(0x59F06292),
    BLUE(0x5942A5F5),
    GREEN(0x5966BB6A),
    YELLOW(0x59FFEB3B),
    ORANGE(0x59FFA726);

    companion object {
        val DEFAULT = YELLOW

        fun fromName(name: String?): HighlighterColor = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
