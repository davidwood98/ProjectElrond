package ai.elrond.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        NotebookEntity::class,
        NotePageEntity::class,
        StrokeEntity::class,
        TodoItemEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class ElrondDatabase : RoomDatabase() {
    abstract fun notebookDao(): NotebookDao
    abstract fun notePageDao(): NotePageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun todoDao(): TodoDao

    companion object {
        private const val DB_NAME = "elrond.db"

        @Volatile
        private var instance: ElrondDatabase? = null

        fun get(context: Context): ElrondDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ElrondDatabase::class.java,
                    DB_NAME,
                ).build().also { instance = it }
            }
    }
}
