package ai.elrond.domain

/**
 * How the editor's note tabs are laid out (a Claude Design "tweak", FA-14). [ATTACHED] docks a tabs
 * row atop the floating tool pod; [SEPARATE] floats the tabs above the note title. The handoff
 * defaults to [SEPARATE].
 */
enum class NoteTabsMode {
    ATTACHED,
    SEPARATE;

    companion object {
        val DEFAULT = SEPARATE

        fun fromName(name: String?): NoteTabsMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
