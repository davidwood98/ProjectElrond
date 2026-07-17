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

    /** Renames the notebook (FA-20). The title is a notebook property, so it survives page reorders. */
    @Query("UPDATE notebooks SET name = :name, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun setName(id: String, name: String, modifiedAt: Long)

    /** Deletes a notebook; its pages (and their strokes/ai-notes/edit-events) cascade via FKs (FA-20). */
    @Query("DELETE FROM notebooks WHERE id = :id")
    suspend fun deleteById(id: String)
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

    /** Pages within a notebook in page order (FA-20) — backs the multi-page editor + page indicator. */
    @Query("SELECT * FROM note_pages WHERE notebookId = :notebookId ORDER BY pageNumber, createdAt")
    fun observeByNotebookOrdered(notebookId: String): Flow<List<NotePageEntity>>

    /** Highest page number in a notebook, or null when it has no pages (FA-20). */
    @Query("SELECT MAX(pageNumber) FROM note_pages WHERE notebookId = :notebookId")
    suspend fun maxPageNumber(notebookId: String): Int?

    /** All pages ordered by last edit — backs the "created X / last edited Y" timeline. */
    @Query("SELECT * FROM note_pages ORDER BY modifiedAt DESC")
    fun observeTimeline(): Flow<List<NotePageEntity>>

    @Query("UPDATE note_pages SET modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun touch(id: String, modifiedAt: Long)

    /** Record that a page was opened (FA-15) — drives the "Recent" 24h window + ordering. */
    @Query("UPDATE note_pages SET lastOpenedAt = :openedAt WHERE id = :id")
    suspend fun markOpened(id: String, openedAt: Long)

    @Query("UPDATE note_pages SET customTitle = :title, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, title: String?, modifiedAt: Long)

    /** Cached page text used by the FA-24b extraction skip-gate. */
    @Query("SELECT contextSummary FROM note_pages WHERE id = :id")
    suspend fun getContextSummary(id: String): String?

    /** Stashes the extracted page text (skip-gate). Does not touch modifiedAt — a silent side write. */
    @Query("UPDATE note_pages SET contextSummary = :summary WHERE id = :id")
    suspend fun setContextSummary(id: String, summary: String)

    /** Toggle a page's bookmark flag (FA-20 page index). */
    @Query("UPDATE note_pages SET isBookmarked = :bookmarked WHERE id = :id")
    suspend fun setBookmarked(id: String, bookmarked: Boolean)

    /** Set a page's position within its notebook (FA-20 page-index reorder). */
    @Query("UPDATE note_pages SET pageNumber = :pageNumber WHERE id = :id")
    suspend fun setPageNumber(id: String, pageNumber: Int)

    /** Strokes cascade-delete via the pageId foreign key. */
    @Query("DELETE FROM note_pages WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * The page a notebook should open on (FA-24 link tap) — most recently viewed, falling back
     * to the cover page (lowest pageNumber) when nothing was opened yet. Null only when the
     * notebook has no pages. Same tie-break as observeNotebookSummaries' in-memory logic.
     */
    @Query(
        "SELECT id FROM note_pages WHERE notebookId = :notebookId " +
            "ORDER BY lastOpenedAt DESC, pageNumber LIMIT 1",
    )
    suspend fun mostRecentlyViewedPageId(notebookId: String): String?
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
interface RecognizedLineDao {
    @Query("SELECT * FROM recognized_lines WHERE pageId = :pageId")
    suspend fun getForPage(pageId: String): List<RecognizedLineEntity>

    /** Upsert: a re-recognized line (same stroke-id key) overwrites its cached text + bounds. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<RecognizedLineEntity>)

    @Query("DELETE FROM recognized_lines WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** Cached text for one line key, or null on a cache miss. */
    @Query("SELECT text FROM recognized_lines WHERE id = :id")
    suspend fun textForId(id: String): String?
}

@Dao
interface TagDao {
    @Insert
    suspend fun insert(tag: TagEntity)

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    /**
     * No UI deletes a whole tag directly (the pickers only assign/remove memberships) —
     * kept for the cascade-delete test and as the seam for a future "manage tags" screen.
     */
    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM tags")
    suspend fun getAll(): List<TagEntity>

    /** One-time colour repair for tags stored with an unreadable dark shade (FA-24 feedback). */
    @Query("UPDATE tags SET colorArgb = :colorArgb WHERE id = :id")
    suspend fun setColor(id: String, colorArgb: Int)

    /**
     * Erases every tag with no remaining notebook membership (FA-24 device feedback): an
     * orphaned tag must vanish from the selection menu. Recreating the same name later gets the
     * same colour back (deterministic per name), so nothing of value is lost.
     */
    @Query("DELETE FROM tags WHERE id NOT IN (SELECT tagId FROM notebook_tags)")
    suspend fun deleteOrphans()
}

/** A notebook-tag membership joined with its tag — mirrors the [PendingTypeContent] projection. */
data class NotebookTagRow(
    val notebookId: String,
    val tagId: String,
    val name: String,
    val colorArgb: Int,
)

@Dao
interface NotebookTagDao {
    /** IGNORE: assigning an already-assigned tag is a harmless no-op (composite PK). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(membership: NotebookTagEntity)

    @Query("DELETE FROM notebook_tags WHERE notebookId = :notebookId AND tagId = :tagId")
    suspend fun remove(notebookId: String, tagId: String)

    /**
     * Every membership with its tag — the Library grid needs all notebooks' tags at once.
     * Newest assignment FIRST (implicit rowid): the header row renders list order left→right,
     * so a new tag generates at the left while older pills keep their right-anchored spots.
     */
    @Query(
        "SELECT nt.notebookId AS notebookId, nt.tagId AS tagId, t.name AS name, " +
            "t.colorArgb AS colorArgb FROM notebook_tags nt JOIN tags t ON t.id = nt.tagId " +
            "ORDER BY nt.rowid DESC",
    )
    fun observeAllWithTag(): Flow<List<NotebookTagRow>>
}

/** One backlink row: a link on [sourcePageId] (in notebook [sourceNotebookId]) pointing here. */
data class BacklinkRow(
    val id: String,
    val sourcePageId: String,
    val sourceNotebookId: String,
    val createdAt: Long,
)

@Dao
interface NotebookLinkDao {
    @Insert
    suspend fun insertAll(links: List<NotebookLinkEntity>)

    @Query("SELECT * FROM notebook_links WHERE sourcePageId = :pageId ORDER BY createdAt")
    suspend fun getForPage(pageId: String): List<NotebookLinkEntity>

    @Query("DELETE FROM notebook_links WHERE sourcePageId = :pageId")
    suspend fun deleteForPage(pageId: String)

    /** Atomic full-page rewrite — mirrors ai_notes auto-save. */
    @Transaction
    suspend fun replaceForPage(pageId: String, links: List<NotebookLinkEntity>) {
        deleteForPage(pageId)
        insertAll(links)
    }

    /** Every link targeting [notebookId] — the "referenced by" list (FA-24 Backlinks). */
    @Query(
        "SELECT nl.id AS id, nl.sourcePageId AS sourcePageId, " +
            "np.notebookId AS sourceNotebookId, nl.createdAt AS createdAt " +
            "FROM notebook_links nl JOIN note_pages np ON np.id = nl.sourcePageId " +
            "WHERE nl.targetNotebookId = :notebookId ORDER BY nl.createdAt DESC",
    )
    fun observeBacklinks(notebookId: String): Flow<List<BacklinkRow>>
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

    /**
     * Dismiss the still-pending TODO suggestions for a page whose normalized content matches
     * [contents] — a manual `/Q` claims the on-canvas popups for items it is re-offering, so the
     * same item can't be added twice (once via the popup, once via the `/Q` sheet).
     */
    @Query(
        "UPDATE pending_suggestions SET dismissed = 1 WHERE pageId = :pageId AND dismissed = 0 " +
            "AND type = 'TODO' AND lower(trim(content)) IN (:contents)",
    )
    suspend fun dismissPendingTodos(pageId: String, contents: List<String>)

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
interface SubjectDao {
    @Insert
    suspend fun insert(subject: SubjectEntity)

    @Update
    suspend fun update(subject: SubjectEntity)

    /** Descendant subjects cascade-delete via the self-referential parentId foreign key. */
    @Query("DELETE FROM subjects WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM subjects WHERE id = :id")
    suspend fun getById(id: String): SubjectEntity?

    @Query("SELECT * FROM subjects ORDER BY sortOrder")
    suspend fun getAll(): List<SubjectEntity>

    @Query("SELECT * FROM subjects ORDER BY sortOrder")
    fun observeAll(): Flow<List<SubjectEntity>>

    @Query("UPDATE subjects SET name = :name, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun rename(id: String, name: String, modifiedAt: Long)

    @Query("UPDATE subjects SET colorId = :colorId, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun setColor(id: String, colorId: Int, modifiedAt: Long)

    @Query("UPDATE subjects SET sortOrder = :sortOrder, modifiedAt = :modifiedAt WHERE id = :id")
    suspend fun setSortOrder(id: String, sortOrder: Long, modifiedAt: Long)
}

@Dao
interface NoteSubjectDao {
    /** File a notebook into a subject; REPLACE keeps the one-row-per-notebook (single-subject) invariant. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(membership: NoteSubjectEntity)

    /** Unfile a notebook (remove its subject membership). */
    @Query("DELETE FROM note_subjects WHERE notebookId = :notebookId")
    suspend fun deleteByNotebook(notebookId: String)

    @Query("SELECT * FROM note_subjects WHERE notebookId = :notebookId")
    suspend fun getForNotebook(notebookId: String): NoteSubjectEntity?

    @Query("SELECT * FROM note_subjects")
    fun observeAll(): Flow<List<NoteSubjectEntity>>
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
