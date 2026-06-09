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
    val tags: List<String> = emptyList(),
    /** AI-generated summary of the page used for organisation and topic detection. */
    val contextSummary: String? = null,
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
    /** JSON-serialized list of stroke input points (x, y, t, pressure, tilt, orientation). */
    val inputsJson: String,
    val createdAt: Long,
    /** True for AI response ink, which is rendered visually distinct from user ink. */
    val isAiInk: Boolean = false,
)

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
 * A background-extracted TODO/calendar item awaiting the user's on-canvas Yes/No
 * decision (FA-2 confirmation flow). On "Yes" it graduates to `todo_items` /
 * `calendar_events`; on "No" it's marked [dismissed] (kept so the same item isn't
 * re-suggested on the next save). [x]/[y] anchor the popup near the detected text.
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
    /** "TODO" or "EVENT" (see ai.elrond.extract.SuggestionType). */
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
    val createdAt: Long,
)
