package ai.elrond.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
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
 * the exported schema JSONs from test assets. Covers the full v1→v11 chain (schema
 * validation) plus the data-touching migrations (e.g. v4→v5 page_edit_events backfill,
 * v10→v11 notebook/page columns + modifiedAt backfill).
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
        ElrondDatabase.MIGRATION_8_9,
        ElrondDatabase.MIGRATION_9_10,
        ElrondDatabase.MIGRATION_10_11,
        ElrondDatabase.MIGRATION_11_12,
        ElrondDatabase.MIGRATION_12_13,
        ElrondDatabase.MIGRATION_13_14,
        ElrondDatabase.MIGRATION_14_15,
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
    fun migrates_v1_to_v15_and_validates_final_schema() {
        helper.createDatabase(TEST_DB, 1).close()
        // Throws if the migrated schema doesn't match the exported v15 schema (FA-21 adds
        // ai_notes.fontScale on top of the FA-20 notebook/page columns).
        helper.runMigrationsAndValidate(TEST_DB, 15, true, *allMigrations).close()
    }

    @Test
    fun migration_14_to_15_adds_ai_note_font_scale_defaulting_to_one() {
        helper.createDatabase(TEST_DB, 14).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt, modifiedAt) VALUES ('nb1', 'N', 1, 1)")
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, " +
                    "lastOpenedAt, tags, contextSummary, pageNumber, isBookmarked) " +
                    "VALUES ('p1', 'nb1', NULL, 1, 1, 1, '', NULL, 1, 0)",
            )
            execSQL(
                "INSERT INTO ai_notes (id, pageId, text, x, y, widthPx, heightPx, createdAt) " +
                    "VALUES ('a1', 'p1', 'hi', 0, 0, 300, NULL, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 15, true, *allMigrations)
        val cols = columnNames(db, "ai_notes")
        var fontScale = -1.0
        db.query("SELECT fontScale FROM ai_notes WHERE id = 'a1'").use { c ->
            if (c.moveToNext()) fontScale = c.getDouble(0)
        }
        db.close()
        assertTrue("ai_notes.fontScale should exist", "fontScale" in cols)
        assertEquals("pre-existing rows default to 1.0", 1.0, fontScale, 0.001)
    }

    @Test
    fun migration_13_to_14_adds_page_style_columns() {
        helper.createDatabase(TEST_DB, 13).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt, modifiedAt) VALUES ('nb1', 'N', 1, 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 14, true, *allMigrations)
        val cols = columnNames(db, "notebooks")
        // Existing rows get NULL for the new columns (= inherit the default).
        var gridSpacing = -1
        var hasPaperColor = false
        db.query("SELECT gridSpacing, paperColor FROM notebooks WHERE id = 'nb1'").use { c ->
            if (c.moveToNext()) {
                gridSpacing = if (c.isNull(0)) -1 else c.getInt(0)
                hasPaperColor = !c.isNull(1)
            }
        }
        db.close()
        assertTrue("notebooks.gridSpacing should exist", "gridSpacing" in cols)
        assertTrue("notebooks.paperColor should exist", "paperColor" in cols)
        assertEquals("pre-existing rows keep NULL gridSpacing", -1, gridSpacing)
        assertTrue("pre-existing rows keep NULL paperColor", !hasPaperColor)
    }

    @Test
    fun migration_12_to_13_rekeys_note_subjects_to_the_notebook() {
        helper.createDatabase(TEST_DB, 12).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt, modifiedAt) VALUES ('nb1', 'N', 1, 1)")
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, " +
                    "lastOpenedAt, tags, contextSummary, pageNumber, isBookmarked) " +
                    "VALUES ('p1', 'nb1', NULL, 1, 1, 1, '', NULL, 1, 0)",
            )
            execSQL(
                "INSERT INTO subjects (id, parentId, name, colorId, sortOrder, createdAt, modifiedAt) " +
                    "VALUES ('s1', NULL, 'Maths', 0, 0, 1, 1)",
            )
            // v12 note_subjects is still keyed by pageId.
            execSQL("INSERT INTO note_subjects (pageId, subjectId) VALUES ('p1', 's1')")
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 13, true, *allMigrations)

        val cols = columnNames(db, "note_subjects")
        val rows = mutableListOf<Pair<String, String>>()
        db.query("SELECT notebookId, subjectId FROM note_subjects").use { c ->
            while (c.moveToNext()) rows.add(c.getString(0) to c.getString(1))
        }
        db.close()

        assertTrue("note_subjects should be keyed by notebookId after v12→v13", "notebookId" in cols)
        // The page's membership migrated to its owning notebook.
        assertEquals(listOf("nb1" to "s1"), rows)
    }

    @Test
    fun migration_10_to_11_adds_notebook_and_page_columns_and_backfills_modifiedAt() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt) VALUES ('nb1', 'N', 4242)")
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, " +
                    "lastOpenedAt, tags, contextSummary) " +
                    "VALUES ('p1', 'nb1', NULL, 1000, 2000, 2000, '', NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, *allMigrations)

        val notebookCols = columnNames(db, "notebooks")
        val pageCols = columnNames(db, "note_pages")
        var notebookModifiedAt = -1L
        db.query("SELECT modifiedAt FROM notebooks WHERE id = 'nb1'").use { c ->
            if (c.moveToNext()) notebookModifiedAt = c.getLong(0)
        }
        var pageNumber = -1
        var isBookmarked = -1
        db.query("SELECT pageNumber, isBookmarked FROM note_pages WHERE id = 'p1'").use { c ->
            if (c.moveToNext()) {
                pageNumber = c.getInt(0)
                isBookmarked = c.getInt(1)
            }
        }
        db.close()

        assertTrue("notebooks.pageNavigationMode should exist", "pageNavigationMode" in notebookCols)
        assertTrue("notebooks.paperStyle should exist", "paperStyle" in notebookCols)
        assertTrue("notebooks.viewOrientation should exist", "viewOrientation" in notebookCols)
        assertTrue("notebooks.templateId should exist", "templateId" in notebookCols)
        assertTrue("notebooks.modifiedAt should exist", "modifiedAt" in notebookCols)
        assertTrue("note_pages.pageNumber should exist", "pageNumber" in pageCols)
        assertTrue("note_pages.isBookmarked should exist", "isBookmarked" in pageCols)
        assertEquals(4242L, notebookModifiedAt) // backfilled from createdAt
        assertEquals(1, pageNumber) // default
        assertEquals(0, isBookmarked) // default (false)
    }

    @Test
    fun migration_9_to_10_creates_the_subjects_and_note_subjects_tables() {
        helper.createDatabase(TEST_DB, 9).close()
        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, *allMigrations)
        val tables = mutableSetOf<String>()
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            while (c.moveToNext()) tables.add(c.getString(0))
        }
        db.close()
        assertTrue("subjects table should exist after v9→v10", "subjects" in tables)
        assertTrue("note_subjects table should exist after v9→v10", "note_subjects" in tables)
    }

    @Test
    fun migration_8_to_9_adds_lastOpenedAt_backfilled_from_modifiedAt() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt) VALUES ('nb1', 'N', 0)")
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, tags, contextSummary) " +
                    "VALUES ('p1', 'nb1', NULL, 1000, 7777, '', NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, *allMigrations)
        var lastOpenedAt = -1L
        db.query("SELECT lastOpenedAt FROM note_pages WHERE id = 'p1'").use { c ->
            if (c.moveToNext()) lastOpenedAt = c.getLong(0)
        }
        db.close()
        assertEquals(7777L, lastOpenedAt) // backfilled from modifiedAt
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

    @Test
    fun migration_11_to_12_explodes_each_page_into_its_own_notebook() {
        helper.createDatabase(TEST_DB, 11).apply {
            execSQL("INSERT INTO notebooks (id, name, createdAt, modifiedAt) VALUES ('def', 'My Notes', 1, 1)")
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, " +
                    "lastOpenedAt, tags, contextSummary, pageNumber, isBookmarked) " +
                    "VALUES ('p1', 'def', 'Note one', 10, 20, 20, '', NULL, 1, 0)",
            )
            execSQL(
                "INSERT INTO note_pages (id, notebookId, customTitle, createdAt, modifiedAt, " +
                    "lastOpenedAt, tags, contextSummary, pageNumber, isBookmarked) " +
                    "VALUES ('p2', 'def', NULL, 30, 40, 40, '', NULL, 1, 0)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(TEST_DB, 12, true, *allMigrations)

        val pageNotebooks = mutableMapOf<String, String>()
        db.query("SELECT id, notebookId FROM note_pages ORDER BY id").use { c ->
            while (c.moveToNext()) pageNotebooks[c.getString(0)] = c.getString(1)
        }
        val notebookIds = mutableSetOf<String>()
        db.query("SELECT id FROM notebooks").use { c ->
            while (c.moveToNext()) notebookIds.add(c.getString(0))
        }
        db.close()

        // Each page now owns its own notebook; the empty shared default ('def') is removed.
        assertEquals("nb-p1", pageNotebooks["p1"])
        assertEquals("nb-p2", pageNotebooks["p2"])
        assertEquals(setOf("nb-p1", "nb-p2"), notebookIds)
    }

    private fun columnNames(db: SupportSQLiteDatabase, table: String): Set<String> {
        val cols = mutableSetOf<String>()
        db.query("PRAGMA table_info($table)").use { c ->
            val nameIndex = c.getColumnIndex("name")
            while (c.moveToNext()) cols.add(c.getString(nameIndex))
        }
        return cols
    }

    companion object {
        private const val TEST_DB = "elrond-migration-test"
    }
}
