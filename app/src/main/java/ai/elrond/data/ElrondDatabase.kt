package ai.elrond.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        NotebookEntity::class,
        NotePageEntity::class,
        StrokeEntity::class,
        TodoItemEntity::class,
        AiNoteEntity::class,
        CalendarEventEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ElrondDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun notePageDao(): NotePageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun todoDao(): TodoDao
    abstract fun aiNoteDao(): AiNoteDao
    abstract fun calendarEventDao(): CalendarEventDao

    companion object {
        private const val DB_NAME = "elrond.db"

        /** v2 adds AI-extraction flag and source-title snapshot to todo_items. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE todo_items ADD COLUMN isAiExtracted INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL("ALTER TABLE todo_items ADD COLUMN sourcePageTitle TEXT")
            }
        }

        /** v3 adds the ai_notes table so AI response boxes persist across restarts. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ai_notes (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        text TEXT NOT NULL,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        widthPx REAL NOT NULL,
                        heightPx REAL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES note_pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ai_notes_pageId ON ai_notes(pageId)")
            }
        }

        /** v4 adds the calendar_events table (AI suggestions + confirmed events). */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS calendar_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        description TEXT,
                        startTime INTEGER NOT NULL,
                        endTime INTEGER NOT NULL,
                        location TEXT,
                        attendees TEXT NOT NULL,
                        calendarId TEXT,
                        externalEventId TEXT,
                        sourcePageId TEXT,
                        isAiSuggested INTEGER NOT NULL,
                        isConfirmed INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sourcePageId) REFERENCES note_pages(id) ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_sourcePageId ON calendar_events(sourcePageId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_calendar_events_startTime ON calendar_events(startTime)")
            }
        }

        @Volatile
        private var instance: ElrondDatabase? = null

        fun get(context: Context): ElrondDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ElrondDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build().also { instance = it }
            }
    }
}
