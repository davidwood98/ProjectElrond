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
        NotebookLinkEntity::class,
    ],
    version = 17,
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
    abstract fun notebookLinkDao(): NotebookLinkDao

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

        /**
         * v11 (FA-20) begins the notebook → multi-page model. Additive only — adds per-notebook
         * settings to `notebooks` (pageNavigationMode / paperStyle / viewOrientation override the
         * global defaults, null = inherit; a templateId placeholder; modifiedAt) and page ordering to
         * `note_pages` (pageNumber, isBookmarked). Behaviour is unchanged until the paged canvas
         * lands; existing notebooks get modifiedAt = createdAt. The "explode each note into its own
         * notebook" + note_subjects re-key happen with the navigation flip in a later migration.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN pageNavigationMode TEXT")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN paperStyle TEXT")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN viewOrientation TEXT")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN templateId TEXT")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN modifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE notebooks SET modifiedAt = createdAt")
                db.execSQL("ALTER TABLE note_pages ADD COLUMN pageNumber INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE note_pages ADD COLUMN isBookmarked INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v12 (FA-20) explodes the legacy single-notebook model: each existing note_page becomes its
         * OWN notebook (id `nb-<pageId>`, display title copied from the page), then the old shared
         * default notebook — now empty — is removed. Data-only (no schema change), so it is
         * behaviour-preserving: the library lists pages via observeTimeline regardless of notebook.
         * From here every note is its own notebook — the prerequisite for the multi-page editor.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. One notebook per existing page (deterministic id derived from the page id).
                db.execSQL(
                    """
                    INSERT INTO notebooks (id, name, createdAt, modifiedAt,
                        pageNavigationMode, paperStyle, viewOrientation, templateId)
                    SELECT 'nb-' || id, COALESCE(customTitle, ''), createdAt, modifiedAt,
                        NULL, NULL, NULL, NULL
                    FROM note_pages
                    """.trimIndent(),
                )
                // 2. Repoint each page to its own new notebook.
                db.execSQL("UPDATE note_pages SET notebookId = 'nb-' || id")
                // 3. Drop notebooks that now have no pages (the old shared default).
                db.execSQL(
                    "DELETE FROM notebooks WHERE id NOT IN (SELECT DISTINCT notebookId FROM note_pages)",
                )
            }
        }

        /**
         * v13 (FA-20) re-keys note_subjects from the page to the **notebook** so filing is per-notebook.
         * Recreates the table keyed by notebookId and maps each old (pageId → subjectId) to the page's
         * owning notebook (1:1 after v12, so no collisions; INSERT OR IGNORE is belt-and-braces).
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE note_subjects_new (
                        notebookId TEXT NOT NULL PRIMARY KEY,
                        subjectId TEXT NOT NULL,
                        FOREIGN KEY(notebookId) REFERENCES notebooks(id) ON DELETE CASCADE,
                        FOREIGN KEY(subjectId) REFERENCES subjects(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO note_subjects_new (notebookId, subjectId)
                    SELECT np.notebookId, ns.subjectId
                    FROM note_subjects ns JOIN note_pages np ON np.id = ns.pageId
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE note_subjects")
                db.execSQL("ALTER TABLE note_subjects_new RENAME TO note_subjects")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_note_subjects_subjectId ON note_subjects(subjectId)",
                )
            }
        }

        /**
         * v14 (FA-20) adds per-notebook page-style columns: `gridSpacing` (line/dot/grid density
         * 1–10) and `paperColor` (paper tint). Both nullable → "inherit the default" when unset.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notebooks ADD COLUMN gridSpacing INTEGER")
                db.execSQL("ALTER TABLE notebooks ADD COLUMN paperColor TEXT")
            }
        }

        /** v15 (FA-21) adds a baked-in font-size multiplier to AI notes for ratio-locked resizing. */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE ai_notes ADD COLUMN fontScale REAL NOT NULL DEFAULT 1.0")
            }
        }

        /**
         * v16 (FA-22) finishes the stroke storage format: `strokes.inputsJson TEXT` becomes
         * `strokes.inputs BLOB` holding the packed binary points directly (no Base64 text layer —
         * ~25% smaller rows, no encode/decode on every save/load). Every existing row is converted
         * here — Base64-compact rows decode straight to bytes; pre-compact legacy-JSON rows are
         * parsed and packed ([StrokeSerialization.storedTextToBlob]) — so the legacy read paths are
         * gone from the app code. Row-by-row with a compiled statement, so a large DB migrates
         * without holding every stroke in memory.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE strokes_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        pageId TEXT NOT NULL,
                        brushFamily TEXT NOT NULL,
                        colorArgb INTEGER NOT NULL,
                        brushSize REAL NOT NULL,
                        brushEpsilon REAL NOT NULL,
                        inputs BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        isAiInk INTEGER NOT NULL,
                        groupId TEXT,
                        FOREIGN KEY(pageId) REFERENCES note_pages(id) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                val insert = db.compileStatement(
                    "INSERT INTO strokes_new (id, pageId, brushFamily, colorArgb, brushSize, " +
                        "brushEpsilon, inputs, createdAt, isAiInk, groupId) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                )
                db.query(
                    "SELECT id, pageId, brushFamily, colorArgb, brushSize, brushEpsilon, " +
                        "inputsJson, createdAt, isAiInk, groupId FROM strokes",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        insert.clearBindings()
                        insert.bindString(1, cursor.getString(0))
                        insert.bindString(2, cursor.getString(1))
                        insert.bindString(3, cursor.getString(2))
                        insert.bindLong(4, cursor.getLong(3))
                        insert.bindDouble(5, cursor.getDouble(4))
                        insert.bindDouble(6, cursor.getDouble(5))
                        insert.bindBlob(7, StrokeSerialization.storedTextToBlob(cursor.getString(6)))
                        insert.bindLong(8, cursor.getLong(7))
                        insert.bindLong(9, cursor.getLong(8))
                        if (cursor.isNull(9)) insert.bindNull(10) else insert.bindString(10, cursor.getString(9))
                        insert.executeInsert()
                    }
                }
                db.execSQL("DROP TABLE strokes")
                db.execSQL("ALTER TABLE strokes_new RENAME TO strokes")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strokes_pageId ON strokes(pageId)")
            }
        }

        /**
         * v17 (FA-24 Phase 1) adds notebook_links: on-canvas link boxes referencing another
         * notebook. sourcePageId cascades with its page; targetNotebookId is SET NULL on
         * target deletion (broken-link state, never a crash).
         */
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notebook_links (
                        id TEXT NOT NULL PRIMARY KEY,
                        sourcePageId TEXT NOT NULL,
                        targetNotebookId TEXT,
                        targetPageId TEXT,
                        x REAL NOT NULL,
                        y REAL NOT NULL,
                        widthPx REAL NOT NULL,
                        heightPx REAL,
                        linkText TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(sourcePageId) REFERENCES note_pages(id) ON DELETE CASCADE,
                        FOREIGN KEY(targetNotebookId) REFERENCES notebooks(id) ON DELETE SET NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_notebook_links_sourcePageId " +
                        "ON notebook_links(sourcePageId)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_notebook_links_targetNotebookId " +
                        "ON notebook_links(targetNotebookId)",
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
                    MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                    MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16,
                    MIGRATION_16_17,
                ).build().also { instance = it }
            }
    }
}
