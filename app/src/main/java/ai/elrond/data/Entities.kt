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
    /** Note page the item was extracted from, if AI-extracted. */
    val sourcePageId: String? = null,
    val createdAt: Long,
    val completedAt: Long? = null,
)
