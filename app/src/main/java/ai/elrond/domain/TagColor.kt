package ai.elrond.domain

/**
 * Deterministic tag colour (FA-24): the tag's NAME hashes to a [SubjectPalette] pastel, so the
 * same name always resolves to the same colour. Resolved once at tag creation and STORED in
 * `tags.colorArgb` — never recomputed at read time (the palette could change; stored colours
 * must not).
 */
object TagColor {

    /** The stored ARGB for a new tag named [name]. `argb` normalizes negative hash codes. */
    fun forName(name: String): Int = SubjectPalette.argb(name.hashCode())
}
