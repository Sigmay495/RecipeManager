package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.core.json.JsonInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/** Tests the staged repositories against real Room transactions and FK/index constraints. */
@RunWith(AndroidJUnit4::class)
class MaterialRecipeRepositoryV2Test {
    @Test
    fun materialSaveKeepsAllGroupsSeasonsAndNormalizesExactAliases() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val saved = foods.save(FoodInputV2(
                name = "　玉ねぎ　", aliases = listOf(" ＯＮＩＯＮ ", "たまねぎ"),
                foodGroups = setOf("VEGETABLE", "GRAIN"), baseUnit = " g ",
                seasonMonths = setOf(3, 4, 5), isMainIngredient = true,
            ))
            val details = foods.find(saved.id)!!
            assertEquals("玉ねぎ", details.master.normalizedName)
            assertEquals(setOf("VEGETABLE", "GRAIN"), details.foodGroups.toSet())
            assertEquals(setOf(3, 4, 5), details.seasonMonths.toSet())
            assertEquals(saved.id, foods.findByExactName(" onion ")?.id)
            assertEquals(saved.id, foods.findByExactName("　たまねぎ　")?.id)
            assertNull(foods.findByExactName("たま"))
            assertTrue(foods.search("たまねぎ").any { it.master.id == saved.id })
        }
    }

    @Test
    fun nameAndAliasCollisionsAreRejectedWithoutPartialWrites() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val first = foods.save(FoodInputV2("玉ねぎ", aliases = listOf("たまねぎ")))
            reject { foods.save(FoodInputV2("別材料", aliases = listOf(" たまねぎ "))) }
            reject { foods.save(FoodInputV2("たまねぎ")) }
            reject { foods.save(FoodInputV2("新材料", aliases = listOf("玉ねぎ"))) }
            assertEquals("1", scalar(database, "SELECT COUNT(*) FROM foodMasters"))
            assertEquals(first.id, foods.findByExactName("たまねぎ")?.id)
        }
    }

    @Test
    fun builtInAttributesStayProtectedButUserAliasesCanBeAdded() = runBlocking {
        withDatabase { database ->
            seedBuiltIn(database)
            val foods = FoodRepositoryV2(database)
            reject { foods.save(FoodInputV2("改名", id = builtIn, expectedRevision = 1)) }
            reject { foods.delete(builtIn, 1, confirmed = true) }
            foods.replaceUserAliases(builtIn, 1, listOf("追加別名"))
            val actual = foods.find(builtIn)!!
            assertEquals("内蔵玉ねぎ", actual.master.name)
            assertEquals("g", actual.master.baseUnit)
            assertFalse(actual.master.userEditable)
            assertEquals(builtIn, foods.findByExactName("追加別名")?.id)
            assertEquals("1", scalar(database, "SELECT COUNT(*) FROM foodMasterAliases WHERE foodMasterId = ?", builtIn))
        }
    }

    @Test
    fun staleMaterialAndRecipeRevisionsRejectChangesAndKeepSavedContent() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val recipes = RecipeRepositoryV2(database)
            val food = foods.save(FoodInputV2("玉ねぎ"))
            val changedFood = foods.save(FoodInputV2("新玉ねぎ", id = food.id, expectedRevision = food.revision))
            reject { foods.save(FoodInputV2("古い編集", id = food.id, expectedRevision = food.revision)) }
            val recipe = recipes.save(recipeInput("炒め物", food.id))
            val changedRecipe = recipes.save(recipeInput("変更済み", food.id).copy(id = recipe.id, expectedRevision = recipe.revision))
            reject { recipes.save(recipeInput("古い料理編集", food.id).copy(id = recipe.id, expectedRevision = recipe.revision)) }
            assertEquals(changedFood, foods.find(food.id)?.master)
            assertEquals(changedRecipe, recipes.find(recipe.id)?.recipe)
            assertEquals("1", scalar(database, "SELECT COUNT(*) FROM recipeIngredients"))
        }
    }

    @Test
    fun materialBaseUnitChangeDoesNotConvertExistingRecipeAmountsOrUnits() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val recipes = RecipeRepositoryV2(database)
            val food = foods.save(FoodInputV2("玉ねぎ", baseUnit = "g"))
            val recipe = recipes.save(recipeInput("料理", food.id, "0.123456789012345678901", "g"))
            foods.save(FoodInputV2("玉ねぎ", baseUnit = "kg", id = food.id, expectedRevision = food.revision))
            val ingredient = recipes.find(recipe.id)!!.ingredients.single()
            assertEquals("0.123456789012345678901", ingredient.amount)
            assertEquals("g", ingredient.unit)
            assertEquals("kg", foods.find(food.id)?.master?.baseUnit)
        }
    }

    @Test
    fun unchangedRecipeSaveKeepsRevisionTimestampAndIngredientIds() = runBlocking {
        withDatabase { database ->
            val recipes = RecipeRepositoryV2(database)
            val input = recipeInput("料理")
            val saved = recipes.save(input)
            val before = recipes.find(saved.id)!!
            val unchanged = recipes.save(input.copy(name = "  料理  ", id = saved.id, expectedRevision = saved.revision))
            assertEquals(saved, unchanged)
            assertEquals(before, recipes.find(saved.id))
        }
    }

    @Test
    fun recipeSearchUsesMaterialAliasesAndFiltersRetiredRecipesWithoutDeletingThem() = runBlocking {
        withDatabase { database ->
            val food = FoodRepositoryV2(database).save(FoodInputV2("玉ねぎ", aliases = listOf("ＯＮＩＯＮ", "特別食材コード別名")))
            val recipes = RecipeRepositoryV2(database)
            val input = recipeInput("炒め物", food.id).copy(cookingTimeMinutes = 20, effortLevel = "EASY")
            val saved = recipes.save(input)
            assertEquals(listOf(saved.id), recipes.search(query = "onion", category = "MAIN", effortLevel = "EASY", maxCookingTimeMinutes = 20).map { it.recipe.id })
            assertEquals(listOf(saved.id), recipes.search(query = "特別食材コード別名").map { it.recipe.id })
            assertTrue(recipes.search(query = "食材コード").isEmpty())
            assertTrue(recipes.search(query = "oni").isEmpty())
            assertTrue(recipes.search(maxCookingTimeMinutes = 19).isEmpty())
            val retired = recipes.save(input.copy(id = saved.id, expectedRevision = saved.revision, proposalPriority = "retired"))
            assertTrue(recipes.search(includeRetired = false).isEmpty())
            assertEquals(listOf(saved.id), recipes.search(query = "onion").map { it.recipe.id })
            assertEquals("retired", recipes.find(saved.id)?.recipe?.proposalPriority)
            assertEquals(saved.revision + 1, retired.revision)
        }
    }

    @Test
    fun recipeAndNewMaterialCommitTogetherAndInvalidLaterIngredientLeavesNothing() = runBlocking {
        withDatabase { database ->
            val recipes = RecipeRepositoryV2(database)
            val input = RecipeInputV2("  新料理  ", "MAIN", listOf(
                RecipeIngredientInputV2("新材料", BigDecimal("1.25"), "g", newFood = FoodInputV2("新材料", foodGroups = setOf("VEGETABLE"))),
                RecipeIngredientInputV2("参照切れ材料", BigDecimal.ONE, "g", foodMasterId = "missing-material"),
            ))
            reject { recipes.save(input) }
            reject { recipes.save(input.copy(ingredients = listOf(RecipeIngredientInputV2("不正分量", BigDecimal.ZERO, "g")))) }
            reject { recipes.save(input.copy(ingredients = listOf(input.ingredients.first().copy(name = "長".repeat(101))))) }
            reject { recipes.save(input.copy(ingredients = listOf(input.ingredients.first().copy(note = "注".repeat(201))))) }
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM recipes"))
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM foodMasters"))
            val saved = recipes.save(input.copy(ingredients = input.ingredients.take(1), proposalPriority = "high"))
            val details = recipes.find(saved.id)!!
            assertEquals("新料理", details.recipe.normalizedName)
            assertEquals("high", details.recipe.proposalPriority)
            assertEquals("VEGETABLE", details.recipe.nutritionGroups)
            assertNotNull(details.ingredients.single().foodMasterId)
            assertEquals("1.25", details.ingredients.single().amount)
        }
    }

    @Test
    fun materialDeletionRequiresConfirmationAndRejectsLiveRecipeReferences() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val recipes = RecipeRepositoryV2(database)
            val food = foods.save(FoodInputV2("玉ねぎ"))
            reject { foods.delete(food.id, food.revision, confirmed = false) }
            val recipe = recipes.save(recipeInput("料理", food.id))
            reject { foods.delete(food.id, food.revision, confirmed = true) }
            reject { recipes.delete(recipe.id, recipe.revision, confirmed = false) }
            assertNotNull(foods.find(food.id))
            recipes.delete(recipe.id, recipe.revision, confirmed = true)
            foods.delete(food.id, food.revision, confirmed = true)
            assertNull(foods.find(food.id))
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM recipeIngredients"))
        }
    }

    @Test
    fun recipeDeletionProtectsHistoryAndKeepsItsIngredients() = runBlocking {
        withDatabase { database ->
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("履歴料理"))
            val db = database.openHelper.writableDatabase
            db.execSQL("INSERT INTO cookingHistories VALUES ('history-2026-11-20','2026-11-20',?,?)", arrayOf(at, at))
            db.execSQL("INSERT INTO historyItems VALUES ('actual','history-2026-11-20',?,'履歴料理','MAIN')", arrayOf(recipe.id))
            reject { recipes.delete(recipe.id, recipe.revision, confirmed = true) }
            assertNotNull(recipes.find(recipe.id))
            assertEquals("1", scalar(database, "SELECT COUNT(*) FROM historyItems"))
            assertEquals("1", scalar(database, "SELECT COUNT(*) FROM recipeIngredients"))
        }
    }

    @Test
    fun recipeCategoryChangeChecksItemAndDayLocksBeforeWriting() = runBlocking {
        withDatabase { database ->
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("固定料理"))
            seedMenuItem(database, recipe.id, itemLocked = true)
            reject { recipes.save(recipeInput("変更", category = "SIDE").copy(id = recipe.id, expectedRevision = recipe.revision)) }
            database.openHelper.writableDatabase.execSQL("UPDATE menuItems SET locked=0")
            database.openHelper.writableDatabase.execSQL("UPDATE menuDays SET dayLocked=1")
            reject { recipes.save(recipeInput("変更", category = "SIDE").copy(id = recipe.id, expectedRevision = recipe.revision)) }
            assertEquals(recipe, recipes.find(recipe.id)?.recipe)
            reject { recipes.delete(recipe.id, recipe.revision, confirmed = true) }
            assertEquals("MAIN", scalar(database, "SELECT role FROM menuItems WHERE id='item'"))
        }
    }

    @Test
    fun draftReferencesProtectRecipeAndMaterialDeletion() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val food = foods.save(FoodInputV2("玉ねぎ"))
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("下書き料理"))
            seedRecipeDraft(database, "draft-protected", recipe.id, food.id, recipe.revision, food.revision)
            reject { recipes.delete(recipe.id, recipe.revision, confirmed = true) }
            reject { foods.delete(food.id, food.revision, confirmed = true) }
            assertNotNull(recipes.find(recipe.id))
            assertNotNull(foods.find(food.id))
            assertEquals("2", scalar(database, "SELECT COUNT(*) FROM draftReferences"))
        }
    }

    @Test
    fun mergeAggregatesExactKnownSubtotalUnknownFlagUsagesAndChecksWithoutConvertingUnits() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val source = foods.save(FoodInputV2("旧玉ねぎ", aliases = listOf("旧たまねぎ")))
            val target = foods.save(FoodInputV2("玉ねぎ"))
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("料理", source.id, "7.123456789012345678901", "g"))
            seedShoppingList(database)
            seedShopping(database, "source-g", source.id, source.name, "1.123456789012345678901", "g", true, true, true, recipe.id, "料理A")
            seedShopping(database, "target-g", target.id, target.name, "2.000", "g", false, true, true, recipe.id, "料理B")
            seedShopping(database, "source-kg", source.id, source.name, "0.5", "kg", false, true, false, recipe.id, "料理C")
            val result = foods.merge(source.id, target.id, source.revision, target.revision, confirmed = true)
            assertTrue(result.mixedExcludedItemIds.isEmpty())
            assertNull(foods.find(source.id))
            assertEquals(target.id, foods.findByExactName("旧たまねぎ")?.id)
            assertEquals("2", scalar(database, "SELECT COUNT(*) FROM shoppingItems"))
            assertEquals("3.123456789012345678901", scalar(database, "SELECT amount FROM shoppingItems WHERE unit='g'"))
            assertEquals("1", scalar(database, "SELECT hasUnknownAmount FROM shoppingItems WHERE unit='g'"))
            assertEquals("1", scalar(database, "SELECT excluded FROM shoppingItems WHERE unit='g'"))
            assertEquals("0.5", scalar(database, "SELECT amount FROM shoppingItems WHERE unit='kg'"))
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM shoppingItems WHERE checked=1"))
            assertEquals("1", scalar(database, "SELECT needsRegeneration FROM shoppingLists"))
            val usage = mapper.readTree(scalar(database, "SELECT recipeUsagesJson FROM shoppingItems WHERE unit='g'"))
            assertEquals(setOf("料理A", "料理B"), usage.map { it.path("recipeNameSnapshot").asText() }.toSet())
            val changed = recipes.find(recipe.id)!!
            assertEquals(target.id, changed.ingredients.single().foodMasterId)
            assertEquals("7.123456789012345678901", changed.ingredients.single().amount)
            assertEquals("g", changed.ingredients.single().unit)
            assertEquals(recipe.revision + 1, changed.recipe.revision)
            assertForeignKeys(database)
        }
    }

    @Test
    fun mergeRequiresBothCurrentRevisionsAndConfirmationAndKeepsMixedExcludedUnchecked() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val source = foods.save(FoodInputV2("旧材料"))
            val target = foods.save(FoodInputV2("材料"))
            seedShoppingList(database)
            seedShopping(database, "source-g", source.id, source.name, null, "g", true, true, true)
            seedShopping(database, "target-g", target.id, target.name, "2.5", "g", false, true, false)
            reject { foods.merge(source.id, target.id, source.revision, target.revision, confirmed = false) }
            reject { foods.merge(source.id, target.id, source.revision + 1, target.revision, confirmed = true) }
            assertEquals("2", scalar(database, "SELECT COUNT(*) FROM shoppingItems"))
            assertNotNull(foods.find(source.id))
            val result = foods.merge(source.id, target.id, source.revision, target.revision, confirmed = true)
            assertEquals(setOf(scalar(database, "SELECT id FROM shoppingItems")!!), result.mixedExcludedItemIds)
            assertEquals("2.5", scalar(database, "SELECT amount FROM shoppingItems"))
            assertEquals("1", scalar(database, "SELECT hasUnknownAmount FROM shoppingItems"))
            assertEquals("0", scalar(database, "SELECT checked FROM shoppingItems"))
            assertEquals("0", scalar(database, "SELECT excluded FROM shoppingItems"))
        }
    }

    @Test
    fun mergeRewritesConflictFreeDraftPayloadIndexAndOnlyChangedBaseRevisions() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val source = foods.save(FoodInputV2("旧材料"))
            val target = foods.save(FoodInputV2("材料"))
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("料理", source.id))
            seedRecipeDraft(database, "draft-clean", recipe.id, source.id, recipe.revision, source.revision, unfinishedCandidate = true)
            foods.merge(source.id, target.id, source.revision, target.revision, confirmed = true)
            val payload = mapper.readTree(scalar(database, "SELECT payloadJson FROM drafts WHERE id='draft-clean'"))
            assertEquals(target.id, payload.path("form").path("ingredients").single().path("foodMasterId").asText())
            assertTrue(payload.path("conflicts").isEmpty)
            assertEquals("", payload.path("foodCandidates").single().path("aliases").single().asText())
            assertEquals("入力中候補", payload.path("foodCandidates").single().path("name").asText())
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM draftReferences WHERE entityId=?", source.id))
            assertEquals("1", scalar(database, "SELECT COUNT(*) FROM draftReferences WHERE entityType='food' AND entityId=?", target.id))
            val bases = mapper.readTree(scalar(database, "SELECT baseRevisionsJson FROM drafts WHERE id='draft-clean'"))
            assertEquals(recipes.find(recipe.id)!!.recipe.revision, bases.single { it.path("entityType").asText() == "recipe" }.path("revision").asLong())
            assertEquals(foods.find(target.id)!!.master.revision, bases.single { it.path("entityType").asText() == "food" }.path("revision").asLong())
            assertForeignKeys(database)
        }
    }

    @Test
    fun mergePreservesOldConflictReasonsAndStaleRecipeBasisInsteadOfHidingChanges() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val source = foods.save(FoodInputV2("旧材料"))
            val target = foods.save(FoodInputV2("材料"))
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("料理", source.id))
            seedRecipeDraft(database, "draft-conflict", recipe.id, source.id, recipe.revision, source.revision, existingConflict = true)
            database.openHelper.writableDatabase.execSQL("UPDATE recipes SET revision=revision+3 WHERE id=?", arrayOf(recipe.id))
            foods.merge(source.id, target.id, source.revision, target.revision, confirmed = true)
            val payload = mapper.readTree(scalar(database, "SELECT payloadJson FROM drafts WHERE id='draft-conflict'"))
            assertEquals(target.id, payload.path("form").path("ingredients").single().path("foodMasterId").asText())
            assertTrue(payload.path("conflicts").any { it.path("reason").asText() == "既存の別編集" })
            val bases = mapper.readTree(scalar(database, "SELECT baseRevisionsJson FROM drafts WHERE id='draft-conflict'"))
            assertEquals(recipe.revision, bases.single { it.path("entityType").asText() == "recipe" }.path("revision").asLong())
            assertEquals("0", scalar(database, "SELECT COUNT(*) FROM draftReferences WHERE entityId=?", source.id))
            assertNotNull(recipes.find(recipe.id))
            assertForeignKeys(database)
        }
    }

    @Test
    fun lateDraftValidationFailureRollsBackIngredientShoppingAliasAndRevisionChanges() = runBlocking {
        withDatabase { database ->
            val foods = FoodRepositoryV2(database)
            val source = foods.save(FoodInputV2("旧材料", aliases = listOf("旧材料別名")))
            val target = foods.save(FoodInputV2("材料"))
            val recipes = RecipeRepositoryV2(database)
            val recipe = recipes.save(recipeInput("料理", source.id, "1.123456789012345678901"))
            seedShoppingList(database)
            seedShopping(database, "source-g", source.id, source.name, "1.125", "g", false, true, true, recipe.id)
            seedShopping(database, "target-g", target.id, target.name, "2.375", "g", false, true, false, recipe.id)
            seedRecipeDraft(database, "draft-invalid", recipe.id, source.id, recipe.revision, source.revision)
            val payload = mapper.readTree(scalar(database, "SELECT payloadJson FROM drafts WHERE id='draft-invalid'"))
                .deepCopy<com.fasterxml.jackson.databind.node.ObjectNode>().put("unknownField", true)
            database.openHelper.writableDatabase.execSQL("UPDATE drafts SET payloadJson=? WHERE id='draft-invalid'", arrayOf(payload.toString()))
            val aliasId = scalar(database, "SELECT id FROM userFoodAliases WHERE foodMasterId=?", source.id)
            val recipeBefore = recipes.find(recipe.id)!!
            reject { foods.merge(source.id, target.id, source.revision, target.revision, confirmed = true) }
            assertEquals(source, foods.find(source.id)?.master)
            assertEquals(target, foods.find(target.id)?.master)
            assertEquals(source.id, foods.findByExactName("旧材料別名")?.id)
            assertEquals(aliasId, scalar(database, "SELECT id FROM userFoodAliases WHERE foodMasterId=?", source.id))
            assertEquals(recipeBefore, recipes.find(recipe.id))
            assertEquals("2", scalar(database, "SELECT COUNT(*) FROM shoppingItems"))
            assertEquals("1.125", scalar(database, "SELECT amount FROM shoppingItems WHERE id='source-g'"))
            assertEquals("2.375", scalar(database, "SELECT amount FROM shoppingItems WHERE id='target-g'"))
            assertEquals("2", scalar(database, "SELECT COUNT(*) FROM shoppingItems WHERE checked=1"))
            assertEquals("0", scalar(database, "SELECT needsRegeneration FROM shoppingLists"))
            assertEquals("2", scalar(database, "SELECT COUNT(*) FROM draftReferences"))
            assertEquals(payload.toString(), scalar(database, "SELECT payloadJson FROM drafts WHERE id='draft-invalid'"))
            assertForeignKeys(database)
        }
    }

    private suspend fun withDatabase(block: suspend (RecipeManagerDatabaseV2) -> Unit) {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), RecipeManagerDatabaseV2::class.java,
        ).build()
        try { block(database) } finally { database.close() }
    }

    private fun recipeInput(name: String, foodId: String? = null, amount: String = "1.5", unit: String = "g", category: String = "MAIN") =
        RecipeInputV2(name, category, listOf(RecipeIngredientInputV2("玉ねぎ", BigDecimal(amount), unit, foodMasterId = foodId)))

    private suspend fun reject(block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull("変更が拒否される必要があります", failure)
        assertTrue("入力または競合の検証で拒否する必要があります: $failure", failure is IllegalArgumentException || failure is IllegalStateException)
    }

    private fun scalar(database: RecipeManagerDatabaseV2, sql: String, vararg arguments: String): String? =
        database.openHelper.writableDatabase.query(sql, arguments).use {
            assertTrue("照会結果がありません: $sql", it.moveToFirst())
            if (it.isNull(0)) null else it.getString(0)
        }

    private fun seedBuiltIn(database: RecipeManagerDatabaseV2) {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO foodMasters VALUES (?,'内蔵玉ねぎ','内蔵玉ねぎ','g',1,0,1)", arrayOf(builtIn))
        db.execSQL("INSERT INTO foodMasterAliases VALUES (?,'内蔵別名','内蔵別名')", arrayOf(builtIn))
        db.execSQL("INSERT INTO foodMasterGroups VALUES (?,'VEGETABLE')", arrayOf(builtIn))
    }

    private suspend fun seedMenuItem(database: RecipeManagerDatabaseV2, recipeId: String, itemLocked: Boolean = false) {
        val recipes = RecipeRepositoryV2(database)
        val side = recipes.save(recipeInput("固定副菜", category = "SIDE"))
        val soup = recipes.save(recipeInput("固定汁物", category = "SOUP"))
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO weeklyMenus VALUES ('week','2026-11-16',?,?,1)", arrayOf(at, at))
        db.execSQL("INSERT INTO menuDays VALUES ('day','week','2026-11-16',0,'','','explicit',0,1)")
        db.execSQL("INSERT INTO menuItems VALUES ('item','day',?,'固定料理','MAIN',?)", arrayOf<Any?>(recipeId, if (itemLocked) 1 else 0))
        db.execSQL("INSERT INTO menuItems VALUES ('side-item','day',?,'固定副菜','SIDE',0)", arrayOf(side.id))
        db.execSQL("INSERT INTO menuItems VALUES ('soup-item','day',?,'固定汁物','SOUP',0)", arrayOf(soup.id))
    }

    private fun seedShoppingList(database: RecipeManagerDatabaseV2) {
        database.openHelper.writableDatabase.execSQL("INSERT INTO shoppingLists VALUES ('list','2026-11-16',?,?,?,0)", arrayOf(at, at, "old-fingerprint"))
    }

    private fun seedShopping(
        database: RecipeManagerDatabaseV2, id: String, foodId: String, name: String, amount: String?, unit: String,
        unknown: Boolean, checked: Boolean, excluded: Boolean, recipeId: String? = null, snapshot: String = "料理",
    ) {
        val usage = mapper.createArrayNode().addObject().apply {
            if (recipeId == null) putNull("recipeId") else put("recipeId", recipeId)
            put("recipeNameSnapshot", snapshot)
        }
        val key = mapper.createArrayNode().add("food").add(foodId).add(unit).toString()
        database.openHelper.writableDatabase.execSQL(
            "INSERT INTO shoppingItems VALUES (?,'list',?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(id, name, name, amount, unit, "[$usage]", if (checked) 1 else 0, if (excluded) 1 else 0, foodId, key, if (unknown) 1 else 0),
        )
    }

    private fun seedRecipeDraft(
        database: RecipeManagerDatabaseV2, id: String, recipeId: String, foodId: String,
        recipeRevision: Long, foodRevision: Long, existingConflict: Boolean = false, unfinishedCandidate: Boolean = false,
    ) {
        val payload = mapper.createObjectNode()
        payload.putObject("form").apply {
            put("name", "入力途中料理"); put("category", "main"); put("cookingTimeMinutes", "20")
            put("effortLevel", "normal"); put("sourceUrl", ""); put("memo", ""); put("proposalPriority", "normal")
            putArray("ingredients").addObject().apply {
                put("localKey", "ingredient-input"); put("name", "旧材料"); put("amount", "入力途中")
                put("unit", "g"); put("note", ""); put("foodMasterId", foodId); putNull("foodCandidateKey")
            }
        }
        payload.putArray("foodCandidates").apply {
            if (unfinishedCandidate) addObject().apply {
                put("localKey", "candidate-input"); put("name", "入力中候補"); put("baseUnit", "")
                putArray("aliases").add(""); putArray("foodGroups"); putArray("seasonMonths"); put("isMainIngredient", false)
            }
        }
        payload.putArray("conflicts").apply {
            if (existingConflict) addObject().put("entityType", "recipe").put("entityId", recipeId).put("reason", "既存の別編集")
        }
        val bases = mapper.createArrayNode().apply {
            addObject().put("entityType", "recipe").put("entityId", recipeId).put("revision", recipeRevision)
            addObject().put("entityType", "food").put("entityId", foodId).put("revision", foodRevision)
        }
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO drafts VALUES (?,'recipe',?,1,?,?,?)", arrayOf(id, recipeId, payload.toString(), bases.toString(), at))
        db.execSQL("INSERT INTO draftReferences VALUES (?,'recipe',?)", arrayOf(id, recipeId))
        db.execSQL("INSERT INTO draftReferences VALUES (?,'food',?)", arrayOf(id, foodId))
    }

    private fun assertForeignKeys(database: RecipeManagerDatabaseV2) {
        database.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    private companion object {
        const val at = "2026-11-22T00:00:00Z"
        const val builtIn = "builtin-protected"
        val mapper = JsonInput.mapper()
    }
}
