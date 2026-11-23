package jp.local.recipemanager.data.local.v2

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import jp.local.recipemanager.core.json.JsonInput
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Staged migration: only registered when all v2 repositories and restore writes are ready. */
object Migration1To2 : Migration(1, 2) {
    private val legacyTables = listOf(
        "foodMasters", "foodMasterAliases", "foodMasterGroups", "foodMasterSeasons",
        "recipes", "recipeIngredients", "weeklyMenus", "menuDays", "menuItems",
        "cookingHistories", "historyItems", "shoppingLists", "shoppingItems", "holidays", "settings", "metadata",
    )
    private val insertOrder = listOf(
        "foodMasters", "foodMasterAliases", "foodMasterGroups", "foodMasterSeasons", "userFoodAliases",
        "recipes", "recipeIngredients", "weeklyMenus", "menuDays", "menuItems", "cookingHistories",
        "historyItems", "planOutcomes", "postponedPlans", "shoppingLists", "shoppingItems",
        "drafts", "draftReferences", "holidays", "settings", "metadata",
    )
    private val dropOrder = listOf(
        "shoppingItems", "shoppingLists", "historyItems", "cookingHistories", "menuItems", "menuDays",
        "weeklyMenus", "recipeIngredients", "recipes", "foodMasterAliases", "foodMasterGroups",
        "foodMasterSeasons", "foodMasters", "holidays", "settings", "metadata",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        // Room owns the upgrade transaction. Never disable foreign keys or use destructive fallback.
        check(db.inTransaction()) { "DB移行はトランザクション内で実行してください" }
        val foreignKeysEnabled = db.query("PRAGMA foreign_keys").use {
            check(it.moveToFirst()) { "外部キー設定を取得できません" }
            it.getInt(0) == 1
        }
        checkForeignKeys(db)
        val source = legacyTables.associateWith { table -> readRows(db, table) }
        LegacyDatabaseValidator.validate(source)
        val converted = convert(source, Instant.now().toString())

        SchemaV2Sql.tables.forEach { (table, sql) ->
            // Room may enable FKs only onOpen, after onUpgrade. Pre-3.26 SQLite does not
            // rewrite FK targets on RENAME with FKs off; retain the final targets in that case.
            val stagingSql = if (foreignKeysEnabled) {
                insertOrder.fold(sql) { text, name -> text.replace("`$name`", "`${name}_v2`") }
            } else sql.replaceFirst("`$table`", "`${table}_v2`")
            // A pre-existing staging table signals an unexpected schema; do not silently reuse it.
            db.execSQL(stagingSql.replace("CREATE TABLE IF NOT EXISTS", "CREATE TABLE"))
            check(table in converted) { "移行定義の不足: $table" }
        }
        insertOrder.forEach { table ->
            converted.getValue(table).forEach { row ->
                val columns = row.keys.joinToString(",") { "`$it`" }
                val placeholders = row.keys.joinToString(",") { "?" }
                db.execSQL("INSERT INTO `${table}_v2` ($columns) VALUES ($placeholders)", row.values.toTypedArray())
            }
            db.query("SELECT COUNT(*) FROM `${table}_v2`").use {
                check(it.moveToFirst() && it.getLong(0) == converted.getValue(table).size.toLong()) {
                    "移行件数が一致しません: $table"
                }
            }
        }
        checkForeignKeys(db)
        dropOrder.forEach { db.execSQL("DROP TABLE `$it`") }
        insertOrder.forEach { db.execSQL("ALTER TABLE `${it}_v2` RENAME TO `$it`") }
        SchemaV2Sql.indices.forEach(db::execSQL)
        checkForeignKeys(db)
        db.query("SELECT id FROM shoppingItems WHERE amount IS NULL AND hasUnknownAmount = 0").use {
            check(!it.moveToFirst()) { "買い物数量の不明情報が失われています" }
        }
    }

    private fun convert(source: Map<String, List<Map<String, Any?>>>, migratedAt: String): Map<String, List<Map<String, Any?>>> {
        val result = insertOrder.associateWith { source[it].orEmpty().map { row -> row.toMutableMap() }.toMutableList() }.toMutableMap()
        fun rows(table: String) = result.getValue(table)
        val protectedRecipes = (source.getValue("menuItems") + source.getValue("historyItems"))
            .map { it.getValue("recipeId") as String }.toSet()
        val deleted = source.getValue("recipes").filter { it["status"] == "DELETED" && it["id"] !in protectedRecipes }
            .map { it["id"] }.toSet()
        val histories = source.getValue("cookingHistories").associate { it["id"] to it.getValue("cookedDate") as String }
        val lastCooked = source.getValue("historyItems").groupBy { it["recipeId"] }.mapValues { (_, items) ->
            items.maxOf { histories.getValue(it["historyId"]) }
        }
        rows("recipes").removeAll { it["id"] in deleted }
        rows("recipeIngredients").removeAll { it["recipeId"] in deleted }
        rows("recipes").forEach { recipe ->
            recipe["proposalPriority"] = if (recipe["status"] == "DELETED") "retired" else "normal"
            recipe["revision"] = 1L
            recipe["lastCookedAt"] = lastCooked[recipe["id"]]
            recipe.keys.removeAll(setOf("status", "deletedAt", "initialLastCookedAt"))
        }
        rows("weeklyMenus").forEach { it["revision"] = 1L }
        rows("menuDays").forEach {
            it["requirementOrigin"] = "legacy"; it["dayLocked"] = 0L; it["revision"] = 1L
        }
        rows("menuItems").forEach { it["locked"] = 0L }
        rows("weeklyMenus").forEach { week ->
            val start = LocalDate.parse(week.getValue("weekStart") as String)
            for (offset in 5L..6L) {
                val date = start.plusDays(offset).toString()
                if (rows("menuDays").none { it["date"] == date }) rows("menuDays").add(mutableMapOf(
                    "id" to UUID.randomUUID().toString(), "weeklyMenuId" to week["id"], "date" to date,
                    "noMeal" to 1L, "noMealReason" to "", "noMealMemo" to "", "requirementOrigin" to "legacy",
                    "dayLocked" to 0L, "revision" to 1L,
                ))
            }
        }
        val userFoods = rows("foodMasters").filter { it["userEditable"] == 1L }.map { it["id"] }.toSet()
        rows("foodMasterAliases").filter { it["foodMasterId"] in userFoods }.forEach { alias ->
            rows("userFoodAliases").add(alias.toMutableMap().apply {
                put("id", UUID.randomUUID().toString()); put("updatedAt", migratedAt)
            })
        }
        rows("foodMasterAliases").removeAll { it["foodMasterId"] in userFoods }
        rows("foodMasters").forEach { it.remove("salesUnit"); it["baseUnit"] = ""; it["revision"] = 1L }
        rows("shoppingLists").forEach { it["sourceFingerprint"] = null; it["needsRegeneration"] = 1L }
        val mapper = JsonInput.mapper()
        rows("shoppingItems").forEach { item ->
            item["hasUnknownAmount"] = if (item["amount"] == null) 1L else 0L
            item["foodMasterId"] = null
            item["aggregationKey"] = mapper.createArrayNode().add("name")
                .add(item.getValue("normalizedName") as String).add((item.getValue("unit") as String).trim()).toString()
            val original = item.remove("recipeNames") as String
            // Preserve the raw snapshot. The old delimiter may also occur inside a recipe name.
            val usages = mapper.createArrayNode()
            if (original.isNotEmpty()) usages.addObject().putNull("recipeId").put("recipeNameSnapshot", original)
            item["recipeUsagesJson"] = usages.toString()
        }
        return result
    }

    private fun readRows(db: SupportSQLiteDatabase, table: String): List<Map<String, Any?>> =
        db.query("SELECT * FROM `$table`").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.columnNames.indices.associate { index ->
                    cursor.getColumnName(index) to when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                        else -> error("移行できない保存型です: $table.${cursor.getColumnName(index)}")
                    }
                })
            }
        }

    private fun checkForeignKeys(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use { check(!it.moveToFirst()) { "DBの外部参照が切れています" } }
    }
}
