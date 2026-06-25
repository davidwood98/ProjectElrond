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
        PageEditEventEntity::class,
        PendingSuggestionEntity::class,
        SubjectEntity::class,
        NoteSubjectEntity::class,
    ],
    version = 10,
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
    abstract fun pageEditEventDao(): PageEditEventDao
    abstract fun pendingSuggestionDao(): PendingSuggestionDao
    abstract fun subjectDao(): SubjectDao
    abstract fun noteSubjectDao(): NoteSubjectDao

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

        /**
         * v5 adds the page_edit_events table so the calendar can mark every day a note was
         * edited (note_pages only keeps the last modifiedAt). Backfills one edit event per
         * existing page whose last edit fell on a different day than its creation.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS page_edit_events (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        editDay INTEGER NOT NULL,
                        editedAt INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES note_pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_page_edit_events_pageId_editDay " +
                        "ON page_edit_events(pageId, editDay)",
                )
                // Best-effort backfill of the last-edit day for pages edited after creation.
                // Uses UTC epoch-day (millis / 86_400_000) for the historical rows.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO page_edit_events (id, pageId, editDay, editedAt)
                    SELECT id || '-edit', id, modifiedAt / 86400000, modifiedAt
                    FROM note_pages
                    WHERE modifiedAt / 86400000 <> createdAt / 86400000
                    """.trimIndent(),
                )
            }
        }

        /** v6 adds the pending_suggestions table for FA-2 background-extraction confirmation popups. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_suggestions (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT NOT NULL,
                        dueAtMillis INTEGER,
                        priority INTEGER NOT NULL,
                        startMillis INTEGER,
                        endMillis INTEGER,
                        location TEXT,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        dismissed INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES note_pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_pending_suggestions_pageId ON pending_suggestions(pageId)",
                )
            }
        }

        /** v7 adds the lasso-selection groupId column to strokes (FA-9), so groups persist. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE strokes ADD COLUMN groupId TEXT")
            }
        }

        /**
         * v8 adds the FA-14 workflow status column to todo_items (0 = to-do, 1 = in progress,
         * 2 = done) for the Kanban board, and backfills already-completed items to DONE.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE todo_items ADD COLUMN status INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE todo_items SET status = 2 WHERE isCompleted = 1")
            }
        }

        /**
         * v9 adds note_pages.lastOpenedAt (FA-15) for the "Recent" list / note tabs, backfilled to
         * each page's last-edit time so existing notes still order sensibly.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE note_pages ADD COLUMN lastOpenedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE note_pages SET lastOpenedAt = modifiedAt")
            }
        }

        /**
         * v10 adds the Subjects feature (FA-16): the `subjects` folder tree (self-referential
         * parentId, ON DELETE CASCADE) and the `note_subjects` membership table (pageId PRIMARY KEY,
         * so a note files into at most one subject). Both FKs cascade. No backfill — every note
         * starts unfiled and the subject tree starts empty.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS subjects (
                        id TEXT NOT NULL PRIMARY KEY,
                        parentId TEXT,
                        name TEXT NOT NULL,
                        colorId INTEGER NOT NULL,
                        sortOrder INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        modifiedAt INTEGER NOT NULL,
                        FOREIGN KEY(parentId) REFERENCES subjects(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_subjects_parentId ON subjects(parentId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS note_subjects (
                        pageId TEXT NOT NULL PRIMARY KEY,
                        subjectId TEXT NOT NULL,
                        FOREIGN KEY(pageId) REFERENCES note_pages(id) ON DELETE CASCADE,
                        FOREIGN KEY(subjectId) REFERENCES subjects(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_note_subjects_subjectId ON note_subjects(subjectId)",
                )
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
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10,
                ).build().also { instance = it }
            }
    }
}
