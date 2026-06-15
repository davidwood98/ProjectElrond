package ai.elrond.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Bug FA-5: the "from [note]" quick link on an AI-extracted to-do must survive an app
 * restart (only disappearing when the source note is deleted). Uses a real *file-backed*
 * Room database that is closed and reopened to genuinely simulate a process restart, rather
 * than an in-memory DB that would be wiped on close.
 */
@RunWith(RobolectricTestRunner::class)
class TodoSourceLinkTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val dbName = "todo-source-link-test.db"

    private fun openDb(): ElrondDatabase =
        Room.databaseBuilder(ctx, ElrondDatabase::class.java, dbName)
            .allowMainThreadQueries()
            .build()

    @After
    fun tearDown() {
        ctx.deleteDatabase(dbName)
    }

    @Test
    fun `an extracted to-do keeps its source link across a database reopen`() = runTest {
        var db = openDb()
        db.notebookDao().insert(NotebookEntity(id = "nb", name = "N", createdAt = 0))
        db.notePageDao().insert(
            NotePageEntity(id = "p1", notebookId = "nb", customTitle = "Standup", createdAt = 0, modifiedAt = 0),
        )
        TodoRepository(db.todoDao()).addExtracted(
            items = listOf(TodoRepository.ExtractedTask("Email Sarah")),
            sourcePageId = "p1",
            sourcePageTitle = "Standup",
        )
        db.close()

        // Reopen the same on-disk database — this is what an app restart looks like.
        db = openDb()
        val item = TodoRepository(db.todoDao()).observeAll().first().single()
        assertEquals("p1", item.sourcePageId)
        assertEquals("Standup", item.sourcePageTitle)
        assertTrue("link must remain tappable after restart", item.hasSourceLink)
        db.close()
    }

    @Test
    fun `deleting the source note clears the link but keeps the to-do`() = runTest {
        val db = openDb()
        db.notebookDao().insert(NotebookEntity(id = "nb", name = "N", createdAt = 0))
        db.notePageDao().insert(
            NotePageEntity(id = "p1", notebookId = "nb", customTitle = "Standup", createdAt = 0, modifiedAt = 0),
        )
        val todoRepo = TodoRepository(db.todoDao())
        todoRepo.addExtracted(
            items = listOf(TodoRepository.ExtractedTask("Email Sarah")),
            sourcePageId = "p1",
            sourcePageTitle = "Standup",
        )

        db.notePageDao().deleteById("p1") // FK ON DELETE SET NULL

        val item = todoRepo.observeAll().first().single()
        assertNull("source link clears only when the note is deleted", item.sourcePageId)
        assertFalse(item.hasSourceLink)
        assertEquals("Email Sarah", item.content)
        db.close()
    }
}
