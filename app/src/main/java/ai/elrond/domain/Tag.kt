package ai.elrond.domain

/**
 * A flat, many-to-many notebook label (FA-24 Phase 2). Independent of the Subjects hierarchy —
 * a notebook files into at most one subject but can carry any number of tags. [colorArgb] is
 * resolved ONCE at creation from the name (see [TagColor]) and stored, never recomputed — the
 * same tag name renders the same colour everywhere, and a future palette change can't silently
 * reflow existing tags.
 */
data class Tag(
    val id: String,
    val name: String,
    val colorArgb: Int,
)
