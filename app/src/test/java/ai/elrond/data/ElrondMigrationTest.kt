package ai.elrond.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
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
        ElrondDatabase.MIGRATION_6_7,
        ElrondDatabase.MIGRATION_7_8,
    )

    /**
     * The exported Room schema JSONs are exposed to the **debug** variant's assets only (and to
     * androidTest for on-device runs) so they never ship in a release build. `./gradlew test`
     * also runs the *release* unit-test variant, where those assets are absent and
     * MigrationTestHelper can't read them — so skip there rather than fail. Full validation still
     * runs in the debug variant (`:app:testDebugUnitTest`) and on-device.
     */
    @Before
    fun requireExportedSchemas() {
        val schemasOnAssets = runCatching {
            InstrumentationRegistry.getInstrumentation().context.assets
                .list(ElrondDatabase::class.java.canonicalName!!)
                .orEmpty().isNotEmpty()
        }.getOrDefault(false)
        Assume.assumeTrue(
            "Room schemas are bundled into the debug variant assets only; skipping in release.",
            schemasOnAssets,
        )
    }

    @Test
    fun migrates_v1_to_v8_and_validates_final_schema() {
        helper.createDatabase(TEST_DB, 1).close()
        // Throws if the migrated schema doesn't match the exported v8 schema (incl. todo_items.status).
        helper.runMigrationsAndValidate(TEST_DB, 8, true, *allMigrations).close()
    }

    @Test
    fun migration_6_to_7_adds_the_strokes_groupId_column() {
        helper.createDatabase(TEST_DB, 6).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, *allMigrations)
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(strokes)").use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) columns.add(c.getString(nameIndex))
        }
        db.close()
        assertTrue("strokes.groupId should exist after v6→v7", "groupId" in columns)
    }

    @Test
    fun migration_7_to_8_adds_status_and_backfills_completed_items_to_done() {
        helper.createDatabase(TEST_DB, 7).apply {
            // A completed item (→ should backfill status=2 DONE) and an open one (→ stays status=0).
            execSQL(
                "INSERT INTO todo_items (id, title, isCompleted, dueAt, priority, sourcePageId, " +
                    "sourcePageTitle, isAiExtracted, createdAt, completedAt) " +
                    "VALUES ('done1', 'Done task', 1, NULL, 0, NULL, NULL, 0, 0, 10)",
            )
            execSQL(
                "INSERT INTO todo_items (id, title, isCompleted, dueAt, priority, sourcePageId, " +
                    "sourcePageTitle, isAiExtracted, createdAt, completedAt) " +
                    "VALUES ('open1', 'Open task', 0, NULL, 0, NULL, NULL, 0, 0, NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, *allMigrations)
        val statusById = mutableMapOf<String, Int>()
        db.query("SELECT id, status FROM todo_items").use { c ->
            while (c.moveToNext()) statusById[c.getString(0)] = c.getInt(1)
        }
        db.close()
        assertEquals(2, statusById["done1"]) // DONE
        assertEquals(0, statusById["open1"]) // TODO
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
