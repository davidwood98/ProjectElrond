package ai.elrond.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {
    @Insert
    suspend fun insert(notebook: NotebookEntity)

    @Update
    suspend fun update(notebook: NotebookEntity)

    @Delete
    suspend fun delete(notebook: NotebookEntity)

    @Query("SELECT * FROM notebooks ORDER BY createdAt")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks WHERE id = :id")
    suspend fun getById(id: String): NotebookEntity?

    @Query("SELECT * FROM notebooks ORDER BY createdAt LIMIT 1")
    suspend fun first(): NotebookEntity?
}

@Dao
interface NotePageDao {
    @Insert
    suspend fun insert(page: NotePageEntity)

    @Update
    suspend fun update(page: NotePageEntity)

    @Delete
    suspend fun delete(page: NotePageEntity)

    @Query("SELECT * FROM note_pages WHERE id = :id")
    suspend fun getById(id: String): NotePageEntity?

    @Query("SELECT * FROM note_pages WHERE notebookId = :notebookId ORDER BY modifiedAt DESC")
    fun observeByNotebook(notebookId: String): Flow<List<NotePageEntity>>

    /** All pages ordered by last edit — backs the "created X / last edited Y" timeline. */
    @Query("SELECT * FROM note_pages ORDER BY modifiedAt DESC")
    fun observeTimeline(): Flow<List<NotePageEntity>>

    @Query("UPDATE note_pages SET modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun touch(id: String, modifiedAt: Long)

    @Query("UPDATE note_pages SET customTitle = :title, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, title: String?, modifiedAt: Long)

    /** Strokes cascade-delete via the pageId foreign key. */
    @Query("DELETE FROM note_pages WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface StrokeDao {
    @Insert
    suspend fun insertAll(strokes: List<StrokeEntity>)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY createdAt")
    suspend fun getForPage(pageId: String): List<StrokeEntity>

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY createdAt")
    fun observeForPage(pageId: String): Flow<List<StrokeEntity>>

    @Query("DELETE FROM strokes WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    /** Atomic full-page rewrite — used by canvas auto-save. */
    @Transaction
    suspend fun replaceForPage(pageId: String, strokes: List<StrokeEntity>) {
        deleteForPage(pageId)
        insertAll(strokes)
    }
}

@Dao
interface CalendarEventDao {
    @Insert
    suspend fun insert(event: CalendarEventEntity)

    @Update
    suspend fun update(event: CalendarEventEntity)

    @Query("DELETE FROM calendar_events WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM calendar_events WHERE id = :id")
    suspend fun getById(id: String): CalendarEventEntity?

    @Query("SELECT * FROM calendar_events ORDER BY startTime")
    fun observeAll(): Flow<List<CalendarEventEntity>>

    /** Unconfirmed AI suggestions awaiting the user's confirm-before-write decision. */
    @Query("SELECT * FROM calendar_events WHERE isAiSuggested = 1 AND isConfirmed = 0 ORDER BY startTime")
    fun observeSuggested(): Flow<List<CalendarEventEntity>>

    @Query("SELECT * FROM calendar_events WHERE startTime >= :startMillis AND startTime < :endMillis ORDER BY startTime")
    suspend fun inRange(startMillis: Long, endMillis: Long): List<CalendarEventEntity>

    /** Titles of AI-suggested events — for de-duplicating background extraction. */
    @Query("SELECT title FROM calendar_events WHERE isAiSuggested = 1")
    suspend fun suggestedTitles(): List<String>
}

@Dao
interface AiNoteDao {
    @Insert
    suspend fun insertAll(notes: List<AiNoteEntity>)

    @Query("SELECT * FROM ai_notes WHERE pageId = :pageId ORDER BY createdAt")
    suspend fun getForPage(pageId: String): List<AiNoteEntity>

    @Query("DELETE FROM ai_notes WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    /** Atomic full-page rewrite — mirrors stroke auto-save. */
    @Transaction
    suspend fun replaceForPage(pageId: String, notes: List<AiNoteEntity>) {
        deleteForPage(pageId)
        insertAll(notes)
    }
}

@Dao
interface PageEditEventDao {
    /** Ignored when an edit for this (pageId, editDay) already exists — one row per day. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PageEditEventEntity)

    @Query("SELECT * FROM page_edit_events")
    fun observeAll(): Flow<List<PageEditEventEntity>>
}

/** Projection of a pending suggestion's type + content for type-namespaced de-dup. */
data class PendingTypeContent(val type: String, val content: String)

@Dao
interface PendingSuggestionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<PendingSuggestionEntity>)

    /** Active (not-dismissed) suggestions for a page — drives the on-canvas popups. */
    @Query("SELECT * FROM pending_suggestions WHERE pageId = :pageId AND dismissed = 0 ORDER BY createdAt")
    fun observeForPage(pageId: String): Flow<List<PendingSuggestionEntity>>

    /** All suggestion contents for a page (incl. dismissed) — for de-dup against re-suggestion. */
    @Query("SELECT content FROM pending_suggestions WHERE pageId = :pageId")
    suspend fun contentsForPage(pageId: String): List<String>

    /** Type + content for every suggestion on a page (incl. dismissed) — type-namespaced de-dup. */
    @Query("SELECT type, content FROM pending_suggestions WHERE pageId = :pageId")
    suspend fun typedContentsForPage(pageId: String): List<PendingTypeContent>

    @Query("SELECT * FROM pending_suggestions WHERE id = :id")
    suspend fun getById(id: String): PendingSuggestionEntity?

    @Query("DELETE FROM pending_suggestions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE pending_suggestions SET dismissed = 1 WHERE id = :id")
    suspend fun markDismissed(id: String)
}

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(item: TodoItemEntity)

    @Insert
    suspend fun insertAll(items: List<TodoItemEntity>)

    @Update
    suspend fun update(item: TodoItemEntity)

    @Delete
    suspend fun delete(item: TodoItemEntity)

    @Query("DELETE FROM todo_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM todo_items WHERE id = :id")
    suspend fun getById(id: String): TodoItemEntity?

    @Query(
        "UPDATE todo_items SET isCompleted = :completed, status = :status, " +
            "completedAt = :completedAt WHERE id = :id",
    )
    suspend fun setCompleted(id: String, completed: Boolean, status: Int, completedAt: Long?)

    /** FA-14: move an item between Kanban columns; keeps the binary isCompleted flag in sync. */
    @Query(
        "UPDATE todo_items SET status = :status, isCompleted = :completed, " +
            "completedAt = :completedAt WHERE id = :id",
    )
    suspend fun setStatus(id: String, status: Int, completed: Boolean, completedAt: Long?)

    /** Active items first by priority, then soonest due date (nulls last). */
    @Query(
        "SELECT * FROM todo_items WHERE isCompleted = 0 " +
            "ORDER BY priority DESC, dueAt IS NULL, dueAt, createdAt DESC",
    )
    fun observeActive(): Flow<List<TodoItemEntity>>

    /** All items, completed last; drives the panel list. */
    @Query("SELECT * FROM todo_items ORDER BY isCompleted, priority DESC, createdAt DESC")
    fun observeAll(): Flow<List<TodoItemEntity>>

    /** Outstanding count for the toolbar badge. */
    @Query("SELECT COUNT(*) FROM todo_items WHERE isCompleted = 0")
    fun observeActiveCount(): Flow<Int>

    /** All item contents — for de-duplicating AI extraction against existing tasks. */
    @Query("SELECT title FROM todo_items")
    suspend fun allContents(): List<String>
}
