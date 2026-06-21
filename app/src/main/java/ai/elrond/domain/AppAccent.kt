package ai.elrond.domain

/**
 * The app's accent colour (a Claude Design "tweak", FA-14) — the single "pop" that tints the
 * toolbar selection, the FAB, and active states app-wide. The accent → `Color` mapping lives in the
 * theme layer (`ui/theme`) so this enum stays Compose-free per the by-layer package rule.
 */
enum class AppAccent {
    BLUE,
    NAVY,
    GREEN,
    PINK;

    companion object {
        val DEFAULT = BLUE

        fun fromName(name: String?): AppAccent = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
