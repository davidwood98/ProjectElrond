package ai.elrond.domain

/**
 * The on-screen orientation a notebook's pages are presented in (FA-20), independent of the
 * physical device orientation. Persisted per notebook.
 *
 * The strokes/toolbar stay upright with the device UI — they do NOT rotate. Instead the page *sheet*
 * itself swaps aspect: [PORTRAIT] is a tall A-ratio sheet (the default); [LANDSCAPE] is a wide
 * A-ratio sheet (its short edge becomes the height), fitted + centred on screen. When the device is
 * rotated an unobtrusive corner button offers to switch the whole notebook's page orientation to
 * match; it can also be set from the page-style menu. Stored stroke coordinates never change.
 */
enum class PageViewOrientation {
    PORTRAIT,
    LANDSCAPE;

    companion object {
        val DEFAULT = PORTRAIT

        /** Parses a stored name, falling back to [DEFAULT] for null/unknown values. */
        fun fromName(name: String?): PageViewOrientation =
            name?.let { runCatching { valueOf(it) }.getOrNull() } ?: DEFAULT
    }
}
