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
    ],
    version = 3,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ElrondDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun notePageDao(): NotePageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun todoDao(): TodoDao
    abstract fun aiNoteDao(): AiNoteDao

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

        @Volatile
        private var instance: ElrondDatabase? = null

        fun get(context: Context): ElrondDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ElrondDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
