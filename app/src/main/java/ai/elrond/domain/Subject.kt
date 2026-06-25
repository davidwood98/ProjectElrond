package ai.elrond.domain

/**
 * A subject = a folder in the note hierarchy (the Subjects feature). Subjects form a tree via
 * [parentId] (null = a root subject). A note lives in **at most one** subject (file-explorer model:
 * a note is either unfiled or filed into exactly one subject); the subject's ancestry chain is what
 * the note's breadcrumb renders. Pure data — the `colorId → Color` bridge lives in the UI layer
 * ([ai.elrond.ui] via [SubjectPalette]), so this stays Compose/Android-free per the by-layer rule.
 *
 * [colorId] indexes [SubjectPalette]; [sortOrder] orders siblings (drag-to-reorder writes it).
 */
data class Subject(
    val id: String,
    val parentId: String?,
    val name: String,
    val colorId: Int,
    val sortOrder: Long,
    val createdAt: Long,
    val modifiedAt: Long,
)
