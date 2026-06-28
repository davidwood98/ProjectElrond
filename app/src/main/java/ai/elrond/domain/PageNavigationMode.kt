package ai.elrond.domain

/**
 * How pages within a notebook are laid out and navigated (FA-20). Persisted per notebook as an
 * override of the global default (a null column = follow the global default, resolved at read).
 *
 * Build note: the paged canvas ships horizontal-turn first, then vertical-continuous; [DEFAULT] is
 * the eventual product default (continuous vertical scroll). The runtime resolver will fall back to
 * whatever mode is implemented while the other is still in development.
 */
enum class PageNavigationMode {
    /** Pages stack vertically; scrolling down moves between them (continuous, with a page break). */
    VERTICAL,

    /** Discrete pages; swiping left/right turns to the next/previous page. */
    HORIZONTAL;

    companion object {
        val DEFAULT = VERTICAL

        /** Parses a stored name, falling back to [DEFAULT] for null/unknown values. */
        fun fromName(name: String?): PageNavigationMode =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
