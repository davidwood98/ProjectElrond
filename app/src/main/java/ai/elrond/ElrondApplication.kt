package ai.elrond

import ai.elrond.data.CalendarRepository
import ai.elrond.data.ElrondDatabase
import ai.elrond.data.NoteRepository
import ai.elrond.data.TodoRepository
import ai.elrond.settings.SettingsRepository
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
            editEventDao = database.pageEditEventDao(),
        )
    }

    val todoRepository: TodoRepository by lazy {
        TodoRepository(todoDao = database.todoDao())
    }

    val calendarRepository: CalendarRepository by lazy {
        CalendarRepository(dao = database.calendarEventDao())
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
