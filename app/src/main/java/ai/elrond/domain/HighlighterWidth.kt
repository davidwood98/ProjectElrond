package ai.elrond.domain

/**
 * Highlighter tip widths (FA-23), in page units (the pen brush is 4f for comparison).
 * Device-retuned 2026-07-07: the default rose to the old widest (20 → 32), and the widest kept
 * the same step above it (default + (default − smallest) = 52).
 */
enum class HighlighterWidth(val size: Float) {
    FINE(12f),
    STANDARD(32f),
    THICK(52f);

    companion object {
        val DEFAULT = STANDARD

        fun fromName(name: String?): HighlighterWidth = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
