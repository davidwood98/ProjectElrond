package ai.elrond

import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import android.app.Application

/** Manual DI container for the POC (Hilt candidate later). */
class ElrondApplication : Application() {

    private val database by lazy { ElrondDatabase.get(this) }

    val noteRepository: NoteRepository by lazy {
        NoteRepository(
            notebookDao = database.notebookDao(),
            pageDao = database.notePageDao(),
            strokeDao = database.strokeDao(),
        )
    }
}
