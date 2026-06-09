package ai.elrond.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Validates the Room schema migrations on a real SQLite database (Robolectric), reading
 * the exported schema JSONs from test assets. Covers the full v1→v5 chain (schema
 * validation) and the v4→v5 backfill that seeds page_edit_events from existing pages.
 */
@RunWith(RobolectricTestRunner::class)
class ElrondMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ElrondDatabase::class.java,
    )

    private val allMigrations = arrayOf(
        ElrondDatabase.MIGRATION_1_2,
        ElrondDatabase.MIGRATION_2_3,
        ElrondDatabase.MIGRATION_3_4,
        ElrondDatabase.MIGRATION_4_5,
        ElrondDatabase.MIGRATION_5_6,
    )

    @Test
    fun migrates_v1_to_v6_and_validates_final_schema() {
        helper.createDatabase(TEST_DB, 1).close()
        // Throws if the migrated schema doesn't match the exported v6 schema (incl. pending_suggestions).
        helper.runMigrationsAndValidate(TEST_DB, 6, true, *allMigrations).close()
    }

    @Test
    fun migration_4_to_5_backfills_edit_days_for_pages_edited_after_creation() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt) VALUES ('nb1', 'N', 0)")
            // Created on epoch-day 0, last-modified on epoch-day 2 → expect one backfilled edit row.
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, tags, contextSummary) " +
                    "VALUES ('p1', 'nb1', NULL, 0, ${2L * 86_400_000L}, '', NULL)",
            )
            // Created and modified on the same day → no backfill row.
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, tags, contextSummary) " +
                    "VALUES ('p2', 'nb1', NULL, 5000, 6000, '', NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 5, true, *allMigrations)
        val rows = mutableListOf<Pair<String, Long>>()
        db.query("SELECT pageId, editDay FROM page_edit_events ORDER BY pageId").use { c ->
            while (c.moveToNext()) rows.add(c.getString(0) to c.getLong(1))
        }
        db.close()

        assertEquals(1, rows.size)
        assertEquals("p1", rows[0].first)
        assertEquals(2L, rows[0].second) // epoch-day 2
    }

    companion object {
        private const val TEST_DB = "elrond-migration-test"
    }
}
