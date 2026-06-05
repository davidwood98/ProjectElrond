package ai.elrond

import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import ai.elrond.data.TodoRepository
import android.app.Application

/** Manual DI container for the POC (Hilt candidate later). */
class ElrondApplication : Application() {

    private val database by lazy { ElrondDatabase.get(this) }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(
            notebookDao = database.notebookDao(),
            pageDao = database.notePageDao(),
            strokeDao = database.strokeDao(),
            aiNoteDao = database.aiNoteDao(),
        )
    }

    val todoRepository: TodoRepository by lazy {
        TodoRepository(todoDao = database.todoDao())
    }
}
