package ai.elrond.domain

/**
 * The note-canvas paper tint (FA-20, per-notebook page-style option). Purely visual — the sheet
 * colour behind the ink. The actual ARGB values live in the ui layer (the enum → `Color` bridge,
 * mirroring [AppAccent]/[PaperStyle]); this stays Compose-free so it can be stored + tested as data.
 */
enum class PaperColor {
    WHITE,
    CREAM,
    YELLOW,
    DRAFTING_BLUE;

    companion object {
        val DEFAULT = WHITE

        fun fromName(name: String?): PaperColor = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
