package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.data.local.RecipeManagerDatabase
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Exercises the actual Room v1 -> v2 open path, including Room's generated schema validation. */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val names = mutableListOf<String>()

    @After
    fun cleanUp() {
        names.forEach(context::deleteDatabase)
    }

    @Test
    fun migrationPreservesGraphAliasesAmountsAndChecksAndRemovesOnlyObsoleteData() {
        val name = createLegacy(::seedValidGraph)
        val startedAt = Instant.now()
        openCurrent(name).useDatabase { current ->
            val db = current.openHelper.writableDatabase
            assertEquals("2", scalar(db, "PRAGMA user_version"))
            assertEquals("3", scalar(db, "SELECT COUNT(*) FROM recipes"))
            assertEquals("normal", scalar(db, "SELECT proposalPriority FROM recipes WHERE id = ?", recipe))
            assertEquals("retired", scalar(db, "SELECT proposalPriority FROM recipes WHERE id = ?", retired))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM recipes WHERE id = ?", unreferencedDeleted))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM recipeIngredients WHERE recipeId = ?", unreferencedDeleted))
            assertEquals("2026-11-09", scalar(db, "SELECT lastCookedAt FROM recipes WHERE id = ?", recipe))
            assertEquals("2026-11-09", scalar(db, "SELECT lastCookedAt FROM recipes WHERE id = ?", retired))
            assertNull(scalar(db, "SELECT lastCookedAt FROM recipes WHERE id = ?", noHistory))
            val recipeColumns = columns(db, "recipes")
            assertFalse(recipeColumns.contains("status"))
            assertFalse(recipeColumns.contains("deletedAt"))
            assertFalse(recipeColumns.contains("initialLastCookedAt"))
            assertEquals("history-2026-11-09", scalar(db, "SELECT historyId FROM historyItems WHERE id = ?", id("history-item-latest")))
            assertEquals("料理名の記録", scalar(db, "SELECT recipeNameSnapshot FROM historyItems WHERE id = ?", id("history-item-latest")))
            assertEquals(preciseAmount, scalar(db, "SELECT amount FROM recipeIngredients WHERE id = ?", id("ingredient-$recipe")))
            assertEquals("g", scalar(db, "SELECT unit FROM recipeIngredients WHERE id = ?", id("ingredient-$recipe")))

            assertEquals("7", scalar(db, "SELECT COUNT(*) FROM menuDays WHERE weeklyMenuId = ?", week))
            assertEquals("7", scalar(db, "SELECT COUNT(*) FROM menuDays WHERE requirementOrigin = 'legacy' AND revision = 1 AND dayLocked = 0"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuItems WHERE locked != 0"))
            assertEquals("旧理由", scalar(db, "SELECT noMealReason FROM menuDays WHERE id = ?", day(4)))
            assertEquals("旧メモ", scalar(db, "SELECT noMealMemo FROM menuDays WHERE id = ?", day(4)))
            listOf("2026-11-14", "2026-11-15").forEach { date ->
                assertEquals("1", scalar(db, "SELECT noMeal FROM menuDays WHERE date = ?", date))
                assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuItems WHERE menuDayId IN (SELECT id FROM menuDays WHERE date = ?)", date))
            }
            assertEquals("1", scalar(db, "SELECT revision FROM weeklyMenus WHERE id = ?", week))

            assertEquals("", scalar(db, "SELECT baseUnit FROM foodMasters WHERE id = ?", userFood))
            assertFalse(columns(db, "foodMasters").contains("salesUnit"))
            assertEquals("1", scalar(db, "SELECT revision FROM foodMasters WHERE id = ?", userFood))
            assertEquals("2", scalar(db, "SELECT COUNT(*) FROM foodMasterGroups WHERE foodMasterId = ?", userFood))
            assertEquals("2", scalar(db, "SELECT COUNT(*) FROM foodMasterSeasons WHERE foodMasterId = ?", userFood))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM foodMasterAliases WHERE foodMasterId = ?", userFood))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM foodMasterAliases WHERE foodMasterId = ?", builtInFood))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM userFoodAliases WHERE foodMasterId = ?", userFood))
            assertEquals("別名 野菜", scalar(db, "SELECT alias FROM userFoodAliases WHERE foodMasterId = ?", userFood))
            assertEquals("別名野菜", scalar(db, "SELECT normalizedAlias FROM userFoodAliases WHERE foodMasterId = ?", userFood))
            UUID.fromString(scalar(db, "SELECT id FROM userFoodAliases WHERE foodMasterId = ?", userFood))
            val aliasTime = Instant.parse(scalar(db, "SELECT updatedAt FROM userFoodAliases WHERE foodMasterId = ?", userFood))
            assertTrue(!aliasTime.isBefore(startedAt) && !aliasTime.isAfter(Instant.now()))

            assertEquals("1", scalar(db, "SELECT needsRegeneration FROM shoppingLists WHERE id = ?", shoppingList))
            assertNull(scalar(db, "SELECT sourceFingerprint FROM shoppingLists WHERE id = ?", shoppingList))
            assertEquals(timestamp, scalar(db, "SELECT sourceMenuUpdatedAt FROM shoppingLists WHERE id = ?", shoppingList))
            assertEquals(preciseAmount, scalar(db, "SELECT amount FROM shoppingItems WHERE id = ?", id("shopping-known")))
            assertEquals("0", scalar(db, "SELECT hasUnknownAmount FROM shoppingItems WHERE id = ?", id("shopping-known")))
            assertNull(scalar(db, "SELECT amount FROM shoppingItems WHERE id = ?", id("shopping-unknown")))
            assertEquals("1", scalar(db, "SELECT hasUnknownAmount FROM shoppingItems WHERE id = ?", id("shopping-unknown")))
            assertEquals("2", scalar(db, "SELECT COUNT(*) FROM shoppingItems WHERE checked = 1 AND excluded = 1"))
            assertNull(scalar(db, "SELECT foodMasterId FROM shoppingItems WHERE id = ?", id("shopping-known")))
            val usages = JSONArray(scalar(db, "SELECT recipeUsagesJson FROM shoppingItems WHERE id = ?", id("shopping-known")))
            assertEquals(1, usages.length())
            assertTrue(usages.getJSONObject(0).isNull("recipeId"))
            assertEquals(legacyRecipeNames, usages.getJSONObject(0).getString("recipeNameSnapshot"))
            listOf("drafts", "draftReferences", "planOutcomes", "postponedPlans").forEach {
                assertEquals("0", scalar(db, "SELECT COUNT(*) FROM $it"))
            }
            assertEquals("14", scalar(db, "SELECT value FROM settings WHERE `key` = 'backupReminderIntervalDays'"))
            assertEquals("legacy", scalar(db, "SELECT value FROM metadata WHERE `key` = 'fixture'"))
            assertEquals("祝日記録", scalar(db, "SELECT name FROM holidays WHERE date = '2026-11-23'"))
            db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        }
    }

    @Test
    fun emptyLegacyDatabaseMigratesAndMatchesGeneratedV2Schema() {
        val name = createLegacy { }
        openCurrent(name).useDatabase {
            assertEquals("2", scalar(it.openHelper.writableDatabase, "PRAGMA user_version"))
            assertEquals("0", scalar(it.openHelper.writableDatabase, "SELECT COUNT(*) FROM recipes"))
        }
    }

    @Test
    fun migrationWithForeignKeysEnabledRetargetsEveryReferenceAndCanRollBack() {
        val name = createLegacy(::seedValidGraph)
        openLegacy(name).useDatabase { legacy ->
            val db = legacy.openHelper.writableDatabase
            assertEquals("1", scalar(db, "PRAGMA foreign_keys"))
            val before = snapshot(db)
            var completedMigration = false
            db.beginTransaction()
            try {
                Migration1To2.migrate(db)
                completedMigration = true
                assertEquals("1", scalar(db, "PRAGMA foreign_keys"))
                assertEquals("3", scalar(db, "SELECT COUNT(*) FROM recipes"))
                assertEquals("7", scalar(db, "SELECT COUNT(*) FROM menuDays WHERE weeklyMenuId = ?", week))
                assertEquals("retired", scalar(db, "SELECT proposalPriority FROM recipes WHERE id = ?", retired))
                assertEquals("1", scalar(db, "SELECT COUNT(*) FROM userFoodAliases WHERE foodMasterId = ?", userFood))
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                tables.forEach { table ->
                    assertFalse("Staged table remains: $table", table.contains("_v2"))
                    db.query("PRAGMA foreign_key_list(`$table`)").use { cursor ->
                        while (cursor.moveToNext()) {
                            val parent = cursor.getString(cursor.getColumnIndexOrThrow("table"))
                            assertFalse("Foreign key still references staged parent: $table -> $parent", parent.contains("_v2"))
                            assertTrue("Foreign key references a missing parent: $parent", parent in tables)
                        }
                    }
                }
            } finally {
                // Deliberately leave the transaction unsuccessful, including all successful DDL.
                db.endTransaction()
            }
            assertTrue("Migration body must finish with foreign_keys enabled", completedMigration)
            assertEquals("1", scalar(db, "PRAGMA user_version"))
            assertEquals(before, snapshot(db))
        }
    }

    @Test
    fun duplicateSameDayRecipeHistoryRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("INSERT INTO historyItems VALUES (?, ?, ?, ?, ?)", arrayOf(id("duplicate-history"), "history-2026-11-09", recipe, "重複", "ONE_DISH"))
    }

    @Test
    fun noMealDayWithDishRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("UPDATE menuDays SET noMeal = 1 WHERE id = ?", arrayOf(day(0)))
    }

    @Test
    fun recipeWithoutIngredientsRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("DELETE FROM recipeIngredients WHERE recipeId = ?", arrayOf(recipe))
    }

    @Test
    fun incompleteThreeDishMenuRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("UPDATE menuItems SET role = 'MAIN' WHERE menuDayId = ?", arrayOf(day(0)))
    }

    @Test
    fun aliasConflictingWithStandardNameRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("UPDATE foodMasterAliases SET alias = '米', normalizedAlias = '米' WHERE foodMasterId = ?", arrayOf(userFood))
    }

    @Test
    fun nonMondayWeekRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("UPDATE weeklyMenus SET weekStart = '2026-11-10' WHERE id = ?", arrayOf(week))
    }

    @Test
    fun dayOutsideParentWeekRejectsAndRollsBackEverything() = assertRejectedAndUnchanged {
        it.execSQL("UPDATE menuDays SET date = '2026-11-16' WHERE id = ?", arrayOf(day(0)))
    }

    @Test
    fun failureAfterTableReplacementRollsBackOriginalSchemaAndAllData() {
        var completedMigrationBody = false
        val failingMigration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Migration1To2.migrate(db)
                // Fail after the real migration has dropped/renamed tables and created indices.
                // SQLiteOpenHelper's upgrade transaction must restore even these completed DDL changes.
                completedMigrationBody = true
                error("Injected failure after completing migration SQL")
            }
        }
        assertRejectedAndUnchanged(failingMigration) { }
        assertTrue("The injected failure must be reached after all migration SQL", completedMigrationBody)
    }

    private fun assertRejectedAndUnchanged(
        migration: Migration = Migration1To2,
        corrupt: (SupportSQLiteDatabase) -> Unit,
    ) {
        val name = createLegacy { db -> seedValidGraph(db); corrupt(db) }
        val before = openLegacy(name).useDatabase { snapshot(it.openHelper.writableDatabase) }
        val candidate = openCurrent(name, migration)
        try {
            try {
                candidate.openHelper.writableDatabase
                fail("Invalid legacy data must stop migration")
            } catch (expected: Exception) {
                // Room wraps migration validation errors. The preserved original DB is checked below.
                assertTrue(expected.message.orEmpty().isNotBlank() || expected.cause != null)
            }
        } finally {
            candidate.close()
        }
        openLegacy(name).useDatabase {
            val db = it.openHelper.writableDatabase
            assertEquals("1", scalar(db, "PRAGMA user_version"))
            assertEquals(before, snapshot(db))
            assertTrue(columns(db, "recipes").contains("initialLastCookedAt"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'userFoodAliases'"))
        }
    }

    private fun createLegacy(seed: (SupportSQLiteDatabase) -> Unit): String {
        val name = "migration-test-${UUID.randomUUID()}.db"
        names += name
        openLegacy(name).useDatabase { seed(it.openHelper.writableDatabase) }
        return name
    }

    private fun openLegacy(name: String) = Room.databaseBuilder(context, RecipeManagerDatabase::class.java, name).build()
    private fun openCurrent(name: String, migration: Migration = Migration1To2) =
        Room.databaseBuilder(context, RecipeManagerDatabaseV2::class.java, name).addMigrations(migration).build()

    private inline fun <T : RoomDatabase, R> T.useDatabase(block: (T) -> R): R =
        try { block(this) } finally { close() }

    private fun seedValidGraph(db: SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO foodMasters VALUES (?, '米', '米', '袋', 1, 0)", arrayOf(builtInFood))
        db.execSQL("INSERT INTO foodMasters VALUES (?, '利用野菜', '利用野菜', '束', 1, 1)", arrayOf(userFood))
        db.execSQL("INSERT INTO foodMasterAliases VALUES (?, '白米', '白米')", arrayOf(builtInFood))
        db.execSQL("INSERT INTO foodMasterAliases VALUES (?, '別名 野菜', '別名野菜')", arrayOf(userFood))
        db.execSQL("INSERT INTO foodMasterGroups VALUES (?, 'GRAIN')", arrayOf(builtInFood))
        listOf("VEGETABLE", "MUSHROOM").forEach { db.execSQL("INSERT INTO foodMasterGroups VALUES (?, ?)", arrayOf(userFood, it)) }
        listOf(1, 11).forEach { db.execSQL("INSERT INTO foodMasterSeasons VALUES (?, ?)", arrayOf<Any?>(userFood, it)) }
        listOf(recipe, retired, unreferencedDeleted, noHistory).forEachIndexed { index, recipeId ->
            val deleted = recipeId == retired || recipeId == unreferencedDeleted
            db.execSQL(
                """INSERT INTO recipes (id, name, normalizedName, category, cookingTimeMinutes, effortLevel, sourceUrl, memo,
                    lastCookedAt, initialLastCookedAt, nutritionGroups, status, deletedAt, createdAt, updatedAt)
                    VALUES (?, ?, ?, 'ONE_DISH', 10, 'NORMAL', NULL, '保持するメモ', '2026-12-31', '2026-12-31', 'VEGETABLE', ?, ?, ?, ?)""",
                arrayOf(recipeId, "料理$index", "料理$index", if (deleted) "DELETED" else "ACTIVE", if (deleted) timestamp else null, timestamp, timestamp),
            )
            db.execSQL(
                "INSERT INTO recipeIngredients VALUES (?, ?, 0, '利用野菜', '利用野菜', ?, 'g', '材料メモ', ?)",
                arrayOf(id("ingredient-$recipeId"), recipeId, preciseAmount, userFood),
            )
        }
        db.execSQL("INSERT INTO weeklyMenus VALUES (?, '2026-11-09', ?, ?)", arrayOf(week, timestamp, timestamp))
        (0..4).forEach { offset ->
            db.execSQL("INSERT INTO menuDays VALUES (?, ?, ?, ?, '旧理由', '旧メモ')", arrayOf<Any?>(day(offset), week, LocalDate.parse("2026-11-09").plusDays(offset.toLong()).toString(), if (offset == 4) 1 else 0))
            if (offset != 4) db.execSQL("INSERT INTO menuItems VALUES (?, ?, ?, '予定料理名', 'ONE_DISH')", arrayOf<Any?>(id("menu-item-$offset"), day(offset), recipe))
        }
        listOf("2026-11-02", "2026-11-09").forEach { date ->
            db.execSQL("INSERT INTO cookingHistories VALUES (?, ?, ?, ?)", arrayOf("history-$date", date, timestamp, timestamp))
        }
        db.execSQL("INSERT INTO historyItems VALUES (?, 'history-2026-11-02', ?, '以前の記録', 'ONE_DISH')", arrayOf(id("history-item-old"), recipe))
        db.execSQL("INSERT INTO historyItems VALUES (?, 'history-2026-11-09', ?, '料理名の記録', 'ONE_DISH')", arrayOf(id("history-item-latest"), recipe))
        db.execSQL("INSERT INTO historyItems VALUES (?, 'history-2026-11-09', ?, '廃止料理の記録', 'ONE_DISH')", arrayOf(id("history-item-retired"), retired))
        db.execSQL("INSERT INTO shoppingLists VALUES (?, '2026-11-09', ?, ?)", arrayOf(shoppingList, timestamp, timestamp))
        db.execSQL("INSERT INTO shoppingItems VALUES (?, ?, '利用野菜', '利用野菜', ?, 'g', ?, 1, 1)", arrayOf(id("shopping-known"), shoppingList, preciseAmount, legacyRecipeNames))
        db.execSQL("INSERT INTO shoppingItems VALUES (?, ?, '利用野菜', '利用野菜', NULL, '本', ?, 1, 1)", arrayOf(id("shopping-unknown"), shoppingList, legacyRecipeNames))
        db.execSQL("INSERT INTO settings VALUES ('backupReminderIntervalDays', '14')")
        db.execSQL("INSERT INTO metadata VALUES ('fixture', 'legacy')")
        db.execSQL("INSERT INTO holidays VALUES ('2026-11-23', '祝日記録', 2026)")
    }

    private fun scalar(db: SupportSQLiteDatabase, sql: String, vararg args: Any?): String? =
        db.query(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

    private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> = db.query("PRAGMA table_info(`$table`)").use {
        buildSet { while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name"))) }
    }

    private fun snapshot(db: SupportSQLiteDatabase): List<String> {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return tables.flatMap { table ->
            db.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
                buildList {
                    add("$table:${cursor.columnNames.joinToString()}")
                    while (cursor.moveToNext()) add((0 until cursor.columnCount).joinToString("|") { index -> if (cursor.isNull(index)) "<NULL>" else cursor.getString(index) })
                }
            }
        }
    }

    companion object {
        private const val timestamp = "2026-11-09T01:02:03Z"
        private const val preciseAmount = "123456789.123456789123456789"
        private const val legacyRecipeNames = "料理,コンマ入り、料理B"
        private const val builtInFood = "builtin-rice"
        private val userFood = id("food")
        private val recipe = id("recipe")
        private val retired = id("retired")
        private val unreferencedDeleted = id("deleted")
        private val noHistory = id("no-history")
        private val week = id("week")
        private val shoppingList = id("shopping-list")
        private fun id(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
        private fun day(offset: Int) = id("day-$offset")
    }
}
