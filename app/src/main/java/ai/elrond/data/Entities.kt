package ai.elrond.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "notebooks")
data class NotebookEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    // FA-20: a notebook becomes a multi-page document. These per-notebook settings override the
    // global defaults; a null column means "follow the global default" (resolved at read time).
    val pageNavigationMode: String? = null,
    val paperStyle: String? = null,
    val viewOrientation: String? = null,
    /** Per-notebook line/dot/grid spacing density (1–10); null = default (FA-20). */
    val gridSpacing: Int? = null,
    /** Per-notebook paper tint (PaperColor name); null = default white (FA-20). */
    val paperColor: String? = null,
    /** Placeholder for a future page-template id (FA-20 deferred); unused for now. */
    val templateId: String? = null,
    /** Last time the notebook (or one of its pages) changed; for recency ordering. */
    val modifiedAt: Long = 0,
)

@Entity(
    tableName = "note_pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("notebookId"), Index("modifiedAt")],
)
data class NotePageEntity(
    @PrimaryKey val id: String,
    val notebookId: String,
    /** Null means the page uses its auto-generated timestamp title. */
    val customTitle: String?,
    val createdAt: Long,
    val modifiedAt: Long,
    /** When the note was last opened (FA-15) — backs the "Recent" list / note tabs. */
    val lastOpenedAt: Long = 0,
    val tags: List<String> = emptyList(),
    /** AI-generated summary of the page used for organisation and topic detection. */
    val contextSummary: String? = null,
    /** FA-20: 1-based position of this page within its notebook (mutable; drag-reorder writes it). */
    val pageNumber: Int = 1,
    /** FA-20: user bookmark flag, surfaced in the page index. */
    val isBookmarked: Boolean = false,
)

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** Stock brush family key — see [StrokeSerialization]. */
    val brushFamily: String,
    val colorArgb: Int,
    val brushSize: Float,
    val brushEpsilon: Float,
    /** Packed binary stroke input points (count header + 25 bytes/point) — see [StrokeSerialization]. */
    val inputs: ByteArray,
    val createdAt: Long,
    /** True for AI response ink, which is rendered visually distinct from user ink. */
    val isAiInk: Boolean = false,
    /**
     * Lasso-selection group id (FA-9). Null when ungrouped; strokes sharing a non-null value
     * form one group that selects together and reloads grouped. Added in DB v7 (MIGRATION_6_7).
     */
    val groupId: String? = null,
) {
    // ByteArray gets reference equality from the generated data-class members; compare contents so
    // entity round-trip assertions (tests) behave structurally.
    override fun equals(other: Any?): Boolean =
        other is StrokeEntity &&
            id == other.id && pageId == other.pageId && brushFamily == other.brushFamily &&
            colorArgb == other.colorArgb && brushSize == other.brushSize &&
            brushEpsilon == other.brushEpsilon && inputs.contentEquals(other.inputs) &&
            createdAt == other.createdAt && isAiInk == other.isAiInk && groupId == other.groupId

    override fun hashCode(): Int = 31 * id.hashCode() + inputs.contentHashCode()
}

/**
 * A persisted AI response rendered on the canvas as handwriting-style text.
 * Position and size are stored so the box stays where the user left it.
 */
@Entity(
    tableName = "ai_notes",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class AiNoteEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val text: String,
    val x: Float,
    val y: Float,
    val widthPx: Float,
    /** Null = wrap content height; set once the user resizes vertically. */
    val heightPx: Float? = null,
    /** Font-size multiplier baked in by a ratio-locked resize (FA-21); 1 = base size. */
    val fontScale: Float = 1f,
    val createdAt: Long,
)

/**
 * A flat notebook label (FA-24 Phase 2). Name is unique (case-sensitive, SQLite BINARY collation
 * — consistent with the rest of the schema; a known limitation, not a bug). [colorArgb] is the
 * colour resolved once at creation from the name — stored, never recomputed.
 */
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorArgb: Int,
)

/**
 * Notebook↔tag membership (FA-24 Phase 2) — genuinely many-to-many on both sides (unlike
 * `note_subjects`, whose single-column PK deliberately enforces one subject per notebook).
 * Both FKs cascade: deleting a notebook or a tag clears its memberships.
 */
@Entity(
    tableName = "notebook_tags",
    primaryKeys = ["notebookId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tagId")],
)
data class NotebookTagEntity(
    val notebookId: String,
    val tagId: String,
)

/**
 * An on-canvas link box referencing another notebook (FA-24 Phase 1). Lives on one page's
 * canvas ([sourcePageId], cascades with it) and points at a whole notebook —
 * [targetNotebookId] is nulled by the FK when the target is deleted (a "broken" link,
 * rendered as "Reference not found"). [targetPageId] is reserved for future page-level
 * links and unused. [linkText] caches the target's title at link time so the label
 * survives a later rename. [createdAt] round-trips verbatim (never re-stamped on save) —
 * it drives the Backlinks ordering.
 */
@Entity(
    tableName = "notebook_links",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourcePageId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["targetNotebookId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourcePageId"), Index("targetNotebookId")],
)
data class NotebookLinkEntity(
    @PrimaryKey val id: String,
    val sourcePageId: String,
    val targetNotebookId: String?,
    val targetPageId: String? = null,
    val x: Float,
    val y: Float,
    val widthPx: Float,
    /** Null = wrap content height, same convention as [AiNoteEntity.heightPx]. */
    val heightPx: Float? = null,
    val linkText: String,
    val createdAt: Long,
)

/**
 * A calendar event — either an unconfirmed AI suggestion ([isAiSuggested] = true,
 * [isConfirmed] = false) or one the user has confirmed. Confirmed events may also
 * have been written to a backing calendar; [externalEventId] holds that id.
 */
@Entity(
    tableName = "calendar_events",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourcePageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourcePageId"), Index("startTime")],
)
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val description: String? = null,
    val startTime: Long,
    val endTime: Long,
    val location: String? = null,
    val attendees: List<String> = emptyList(),
    val calendarId: String? = null,
    /** Id in the backing calendar once written there (else null). */
    val externalEventId: String? = null,
    val sourcePageId: String? = null,
    val isAiSuggested: Boolean = false,
    val isConfirmed: Boolean = false,
    val createdAt: Long,
)

@Entity(
    tableName = "todo_items",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourcePageId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("sourcePageId")],
)
data class TodoItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isCompleted: Boolean = false,
    /** FA-14 workflow status ordinal: 0 = to-do, 1 = in progress, 2 = done. Kept in sync with
     *  [isCompleted] (done ⇔ isCompleted) at the repository boundary. */
    val status: Int = 0,
    val dueAt: Long? = null,
    /** 0 = none, 1 = low, 2 = medium, 3 = high. */
    val priority: Int = 0,
    /** Note page this item links back to; null once that page is deleted (SET_NULL). */
    val sourcePageId: String? = null,
    /** Snapshot of the source page title, kept so the link label survives page deletion. */
    val sourcePageTitle: String? = null,
    /** True when the AI extracted this from note content; false for manual entries. */
    val isAiExtracted: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
)

/**
 * One row per note page per local day on which the page was edited. note_pages only
 * keeps a single (overwritten) modifiedAt, so this table is what lets the calendar
 * mark *every* day a note was touched — not just the latest. Deduped to one row per
 * day via the unique (pageId, editDay) index (inserts use IGNORE).
 */
@Entity(
    tableName = "page_edit_events",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["pageId", "editDay"], unique = true)],
)
data class PageEditEventEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** Local day of the edit as an epoch-day (java.time.LocalDate.toEpochDay()). */
    val editDay: Long,
    val editedAt: Long,
)

/**
 * A subject = a folder in the note hierarchy (Subjects feature, DB v10). Self-referential tree via
 * [parentId] (null = root); deleting a subject cascade-deletes its descendant subjects (and, via the
 * [NoteSubjectEntity] FK, un-files their notes — the notes themselves are never deleted).
 */
@Entity(
    tableName = "subjects",
    foreignKeys = [
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("parentId")],
)
data class SubjectEntity(
    @PrimaryKey val id: String,
    /** Parent subject id; null for a root subject. */
    val parentId: String? = null,
    val name: String,
    /** Index into [ai.elrond.domain.SubjectPalette]. */
    val colorId: Int,
    /** Position among same-parent siblings (drag-to-reorder writes it). */
    val sortOrder: Long,
    val createdAt: Long,
    val modifiedAt: Long,
)

/**
 * Notebook→subject membership (Subjects feature; re-keyed from the page to the **notebook** in FA-20
 * DB v13). [notebookId] is the primary key, so a notebook files into **at most one** subject
 * (file-explorer model); no row = unfiled. Both foreign keys cascade, so deleting a notebook or a
 * subject removes the membership (never the notebook's pages via the subject side).
 */
@Entity(
    tableName = "note_subjects",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SubjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["subjectId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("subjectId")],
)
data class NoteSubjectEntity(
    @PrimaryKey val notebookId: String,
    val subjectId: String,
)

/**
 * One recognized handwriting line, cached so AI features don't re-run ML Kit on unchanged ink
 * (FA-24b). [id] is the SHA-256 key of pageId + the line's ordered [CanvasStroke] ids (see
 * `ai.elrond.domain.recognizedLineKey`), so any membership change (stroke added/erased, lines
 * merged/split) yields a fresh key → automatic invalidation. [strokeIds] is the ordered,
 * comma-joined id list the key derives from (kept for debugging/inspection). Bounds are
 * recomputed on every save (a lasso move keeps the ids ⇒ same key ⇒ text stays, bounds refresh).
 */
@Entity(
    tableName = "recognized_lines",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class RecognizedLineEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** Ordered, comma-joined [CanvasStroke] ids forming the line. */
    val strokeIds: String,
    val text: String,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val recognizedAt: Long,
)

/**
 * A background-extracted TODO/calendar item awaiting the user's confirmation
 * (FA-2 confirmation flow). On "Yes" it graduates to `todo_items` / `calendar_events`;
 * either decision marks the row [dismissed] (i.e. handled) and KEEPS it, so the same item
 * is never re-suggested for this page on a later save (the de-dup set spans handled rows).
 * [x]/[y] anchor any on-canvas affordance near the detected text.
 */
@Entity(
    tableName = "pending_suggestions",
    foreignKeys = [
        ForeignKey(
            entity = NotePageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pageId")],
)
data class PendingSuggestionEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    /** "TODO" or "EVENT" (see ai.elrond.domain.SuggestionType). */
    val type: String,
    val content: String,
    val dueAtMillis: Long? = null,
    val priority: Int = 0,
    val startMillis: Long? = null,
    val endMillis: Long? = null,
    val location: String? = null,
    val x: Float,
    val y: Float,
    val dismissed: Boolean = false,
    /**
     * True only when the user *explicitly rejected* this suggestion (left it unchecked on "Add
     * selected"). `dismissed = 1, rejected = 0` is an *ignored* (not-now) row. Rejected lines stay
     * silent under `/Q`; ignored lines are re-offered. See [PendingSuggestionDao.rejectedContentsForPage].
     */
    val rejected: Boolean = false,
    val createdAt: Long,
)
