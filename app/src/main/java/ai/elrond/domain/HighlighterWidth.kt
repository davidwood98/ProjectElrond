package ai.elrond.domain

/** Highlighter tip widths (FA-23), in page units (the pen brush is 4f for comparison). */
enum class HighlighterWidth(val size: Float) {
    FINE(12f),
    STANDARD(20f),
    THICK(32f);

    companion object {
        val DEFAULT = STANDARD

        fun fromName(name: String?): HighlighterWidth = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
