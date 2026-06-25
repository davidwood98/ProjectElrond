package ai.elrond.domain

/**
 * Colour treatment for the AI mark — the organic-loader animation AND the static AI logo
 * (FA-17, from the `organic-loaders` Claude Design handoff). [COLOR] uses the multi-colour Leap
 * palette (the `c` variants, e.g. 17c); [BLACK] uses a single ink. The mode → actual colours /
 * drawable bridge lives in the `ui` layer so this enum stays Compose-free per the by-layer rule.
 * (The handoff's `white` variant is intentionally not offered.)
 */
enum class AiColorMode {
    COLOR,
    BLACK;

    companion object {
        val DEFAULT = COLOR

        fun fromName(name: String?): AiColorMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
