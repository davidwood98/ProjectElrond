package ai.elrond.domain

/**
 * The note-canvas paper background (a Claude Design "tweak", FA-14). The handoff's editor mock
 * defaults to [DOTS]; [RULED] draws horizontal rules; [PLAIN] is a blank white page (the app's
 * pre-FA-14 look). Purely visual — it sits behind the ink and never affects recognition.
 */
enum class PaperStyle {
    RULED,
    PLAIN,
    DOTS;

    companion object {
        /** The handoff editor defaults to a dotted page. */
        val DEFAULT = DOTS

        fun fromName(name: String?): PaperStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
