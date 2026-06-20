package ai.elrond.settings

/**
 * How the active note-tool is highlighted in the toolbar — the three "selected" directions from the
 * Claude Design handoff. [SOFT_TILE] is the recommended default; [FILLED] and [UNDERLINE] are
 * user-selectable alternatives (Settings → Selected tool style).
 */
enum class ToolSelectedTreatment {
    /** A · soft accent-tinted tile behind the icon (recommended default). */
    SOFT_TILE,

    /** B · solid accent fill behind the icon. */
    FILLED,

    /** C · plain icon with a short accent underline bar. */
    UNDERLINE;

    companion object {
        val DEFAULT = SOFT_TILE

        /** Parses a stored name back to a treatment, falling back to [DEFAULT]. */
        fun fromName(name: String?): ToolSelectedTreatment =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
