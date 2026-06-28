package ai.elrond.domain

/**
 * The on-screen orientation a notebook's pages are presented in (FA-20), independent of the
 * physical device orientation. Persisted per notebook.
 *
 * The page's logical coordinate space is always portrait; [LANDSCAPE] rigidly rotates the whole
 * page 90° and fits it to the screen for readability (strokes rotate *with* the page — nothing
 * reflows, and stored coordinates never change). Physical device rotation is the primary way to
 * write in landscape; this setting is the explicit, persisted preference for a given notebook.
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
