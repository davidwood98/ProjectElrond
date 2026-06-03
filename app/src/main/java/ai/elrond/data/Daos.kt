package ai.elrond.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
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
}

@Dao
interface TodoDao {
    @Insert
    suspend fun insert(item: TodoItemEntity)

    @Update
    suspend fun update(item: TodoItemEntity)

    @Delete
    suspend fun delete(item: TodoItemEntity)

    @Query("SELECT * FROM todo_items WHERE isCompleted = 0 ORDER BY priority DESC, dueAt IS NULL, dueAt")
    fun observeActive(): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_items ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TodoItemEntity>>
}
