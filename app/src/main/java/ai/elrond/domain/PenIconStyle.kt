package ai.elrond.domain

/**
 * Pen-family toolbar icon style (a Claude Design "tweak", FA-14). [BODY] draws the whole tool
 * (the handoff's `ic-pen` etc.); [TIP] draws only the writing tip (`ic-pen-tip` etc.). Applies to
 * the pen / highlighter / pencil tools in the note toolbar.
 */
enum class PenIconStyle {
    BODY,
    TIP;

    companion object {
        val DEFAULT = BODY

        fun fromName(name: String?): PenIconStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
