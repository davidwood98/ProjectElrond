package ai.elrond.domain

/**
 * The note-canvas paper background (a Claude Design "tweak", FA-14; per-notebook override, FA-20).
 * The handoff's editor mock defaults to [DOTS]; [RULED] draws horizontal rules; [GRID] draws a
 * square grid; [PLAIN] is a blank page. Purely visual — it sits behind the ink and never affects
 * recognition. The line/dot/grid spacing is scaled by a separate density setting (1–10).
 */
enum class PaperStyle {
    RULED,
    PLAIN,
    DOTS,
    GRID;

    companion object {
        /** The handoff editor defaults to a dotted page. */
        val DEFAULT = DOTS

        fun fromName(name: String?): PaperStyle = entries.firstOrNull { it.name == name } ?: DEFAULT

        /** Grid/line/dot spacing density (1 = most compact … 10 = least); 5 = the design default. */
        const val DEFAULT_GRID_SPACING = 5
        const val MIN_GRID_SPACING = 1
        const val MAX_GRID_SPACING = 10
    }
}
