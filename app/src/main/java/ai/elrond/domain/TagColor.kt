package ai.elrond.domain

/**
 * Deterministic tag colouring (FA-24): the tag's NAME hashes to a [SubjectPalette] colour, so
 * the same name always resolves to the same colour. Resolved once at tag creation and stored in
 * `tags.colorArgb` — never recomputed at read time (the palette could change; stored colours
 * must not).
 *
 * Device feedback (2026-07-12): tags use the SAME colour options as the subject palette
 * EXCEPT each hue's darkest shade — the pill's dark text is unreadable on it. [isReadable]
 * lets the repository repair tags that were stored with a dark shade before this rule.
 */
object TagColor {

    /** The palette minus each hue's darkest shade (shade index [SubjectPalette.SHADE_COUNT]-1). */
    private val readable: List<Int> = SubjectPalette.colors.filterIndexed { index, _ ->
        index % SubjectPalette.SHADE_COUNT != SubjectPalette.SHADE_COUNT - 1
    }

    private val readableSet: Set<Int> = readable.toSet()

    /** The stored ARGB for a new tag named [name] — always one of the readable shades. */
    fun forName(name: String): Int {
        val index = ((name.hashCode() % readable.size) + readable.size) % readable.size
        return readable[index]
    }

    /** True when [argb] is one of the readable tag shades (false for a legacy dark-shade tag). */
    fun isReadable(argb: Int): Boolean = argb in readableSet
}
