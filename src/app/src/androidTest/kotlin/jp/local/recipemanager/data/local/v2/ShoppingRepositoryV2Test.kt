package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.core.json.JsonInput
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ShoppingRepositoryV2Test {
    private val start = LocalDate.parse("2026-11-23")

    @Test fun generationUsesRequiredDaysKeepsPrecisionSeparatesUnitsAndWarnsAboutUnsetDays() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val first = recipe(db, "料理A", food.id, "0.1234567890123456789", "g")
            val second = recipe(db, "料理B", food.id, "0.2", "g")
            val third = recipe(db, "料理C", food.id, "1", "kg")
            saveDay(db, start, first.id); saveDay(db, start.plusDays(1), second.id); saveDay(db, start.plusDays(2), third.id)
            val shopping = ShoppingRepositoryV2(db)
            val generated = shopping.generate(start)
            assertEquals(2, generated.data.items.size)
            val grams = generated.data.items.single { it.unit == "g" }
            assertEquals("0.3234567890123456789", grams.amount)
            assertFalse(grams.hasUnknownAmount)
            assertEquals("1", generated.data.items.single { it.unit == "kg" }.amount)
            val usages = JsonInput.mapper().readTree(grams.recipeUsagesJson)
            assertEquals(setOf(first.id, second.id), usages.map { it.path("recipeId").asText() }.toSet())
            assertEquals(2, generated.warnings.count { it.contains("未設定") })
            assertFalse(shopping.week(start).needsRegeneration)
            assertForeignKeys(db)
        }
    }

    @Test fun knownSubtotalAndUnknownOnlyAmountsPersistAndAlwaysClearPurchasedOnRegeneration() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = RecipeRepositoryV2(db).save(RecipeInputV2("料理", "ONE_DISH", listOf(
                RecipeIngredientInputV2("材料", BigDecimal("1.25"), "g", foodMasterId = food.id),
                RecipeIngredientInputV2("材料", null, "g", foodMasterId = food.id),
                RecipeIngredientInputV2("材料", null, "本", foodMasterId = food.id),
            )))
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val initial = shopping.generate(start)
            initial.data.items.forEach { shopping.updateState(it, checked = true, excluded = true) }
            val generated = shopping.generate(start)
            val mixed = generated.data.items.single { it.unit == "g" }
            assertEquals("1.25", mixed.amount); assertTrue(mixed.hasUnknownAmount)
            val unknown = generated.data.items.single { it.unit == "本" }
            assertNull(unknown.amount); assertTrue(unknown.hasUnknownAmount)
            assertTrue(generated.data.items.all { !it.checked && it.excluded })
            assertTrue(generated.warnings.any { it.contains("数量不明") })
            assertEquals(generated.data, shopping.week(start).data)
        }
    }

    @Test fun sameAndReducedKnownAmountsKeepPurchasedButIncreasesClearItAndPreserveExclusion() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = recipe(db, "料理", food.id, "1.0")
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val initial = shopping.generate(start).data.items.single()
            shopping.updateState(initial, true, true)
            changeIngredient(db, dish.id, food.id, "1.00", "g")
            val same = shopping.generate(start).data.items.single()
            assertTrue(same.checked); assertTrue(same.excluded)
            assertEquals(initial.id, same.id)
            changeIngredient(db, dish.id, food.id, "0.5", "g")
            assertTrue(shopping.week(start).needsRegeneration)
            val less = shopping.generate(start).data.items.single()
            assertTrue(less.checked); assertTrue(less.excluded)
            reject { shopping.updateState(same, false, false) }
            changeIngredient(db, dish.id, food.id, "2.0", "g")
            val more = shopping.generate(start)
            assertFalse(more.data.items.single().checked); assertTrue(more.data.items.single().excluded)
            assertTrue(more.warnings.any { it.contains("増えた") })
        }
    }

    @Test fun unitChangeNeverConvertsAmountAndKeepsTraceableExclusionButClearsPurchased() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = recipe(db, "料理", food.id, "1", "g")
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val initial = shopping.generate(start).data.items.single()
            shopping.updateState(initial, true, true)
            changeIngredient(db, dish.id, food.id, "1", "kg")
            val updated = shopping.generate(start).data.items.single()
            assertEquals("1", updated.amount); assertEquals("kg", updated.unit)
            assertFalse(updated.checked); assertTrue(updated.excluded)
            reject { shopping.updateState(initial, false, false) }
        }
    }

    @Test fun unchangedUncheckedRowDoesNotWarnAboutPurchasedStateOnAnotherUnit() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = RecipeRepositoryV2(db).save(RecipeInputV2("料理", "ONE_DISH", listOf(
                RecipeIngredientInputV2("材料", BigDecimal.ONE, "g", foodMasterId = food.id),
                RecipeIngredientInputV2("材料", BigDecimal.ONE, "kg", foodMasterId = food.id),
            )))
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val old = shopping.generate(start)
            shopping.updateState(old.data.items.single { it.unit == "kg" }, true)
            val generated = shopping.generate(start)
            assertTrue(generated.data.items.single { it.unit == "kg" }.checked)
            assertFalse(generated.data.items.single { it.unit == "g" }.checked)
            assertFalse(generated.warnings.any { it.contains("解除") })
        }
    }

    @Test fun outcomeAndPendingPostponementRecordsNeverStaleOrRemoveExistingQuantities() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = recipe(db, "料理", food.id, "3")
            val original = saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val generated = shopping.generate(start)
            val marked = shopping.updateState(generated.data.items.single(), true, true)
            val history = HistoryRepositoryV2(db)
            history.recordCooking(original.day.id, original.day.revision, emptyMap())
            assertFalse(shopping.week(start).needsRegeneration)
            assertEquals(marked, shopping.week(start).data!!.items.single())
            var day = MenuRepositoryV2(db).week(start).days.first()
            history.clearOutcome(day.items.single().id, day.day.revision, true)
            day = MenuRepositoryV2(db).week(start).days.first()
            history.postpone(day.items.single().id, start.plusDays(1), day.day.revision)
            assertFalse(shopping.week(start).needsRegeneration)
            assertEquals(marked, shopping.week(start).data!!.items.single())
        }
    }

    @Test fun confirmedPostponementIncludesBothSavedSourceAndTargetUntilUserChangesMenu() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = recipe(db, "料理", food.id, "1")
            val source = saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            shopping.updateState(shopping.generate(start).data.items.single(), true, true)
            val history = HistoryRepositoryV2(db)
            val pending = history.postpone(source.items.single().id, start.plusDays(1), source.day.revision)
            val week = MenuRepositoryV2(db).week(start)
            history.confirmPostponement(pending.id, pending.updatedAt, week.days.first().day.revision,
                MenuDayInputV2(start.plusDays(1), false, listOf(MenuSelectionV2(dish.id, "ONE_DISH"))), week.menu!!.revision, null, true)
            assertTrue(shopping.week(start).needsRegeneration)
            val item = shopping.generate(start).data.items.single()
            assertEquals("2", item.amount); assertFalse(item.checked); assertTrue(item.excluded)
        }
    }

    @Test fun materialMergeKeepsUnknownFlagAndDoesNotRestoreClearedPurchasedOrMixedExclusion() = runBlocking {
        withDatabase { db ->
            val foods = FoodRepositoryV2(db)
            val source = foods.save(FoodInputV2("旧材料"))
            val target = foods.save(FoodInputV2("材料"))
            val first = recipe(db, "料理A", source.id, "1.25")
            val second = recipe(db, "料理B", target.id, null)
            saveDay(db, start, first.id); saveDay(db, start.plusDays(1), second.id)
            val shopping = ShoppingRepositoryV2(db)
            val initial = shopping.generate(start)
            initial.data.items.forEach { shopping.updateState(it, true, it.foodMasterId == source.id) }
            foods.merge(source.id, target.id, source.revision, target.revision, true)
            assertTrue(shopping.week(start).needsRegeneration)
            val generated = shopping.generate(start).data.items.single()
            assertEquals(target.id, generated.foodMasterId)
            assertEquals("1.25", generated.amount); assertTrue(generated.hasUnknownAmount)
            assertFalse(generated.checked); assertFalse(generated.excluded)
            assertForeignKeys(db)
        }
    }

    @Test fun newlyResolvedAliasAndMasterRevisionChangesAreDetectedWithoutChangingRecipeQuantities() = runBlocking {
        withDatabase { db ->
            val dish = recipe(db, "料理", null, "1")
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val original = shopping.generate(start).data.items.single()
            assertNull(original.foodMasterId)
            shopping.updateState(original, true, true)
            val foods = FoodRepositoryV2(db)
            val food = foods.save(FoodInputV2("標準材料", aliases = listOf("材料"), baseUnit = "g"))
            assertTrue(shopping.week(start).needsRegeneration)
            var generated = shopping.generate(start).data.items.single()
            assertEquals(food.id, generated.foodMasterId); assertTrue(generated.checked); assertTrue(generated.excluded)
            foods.save(FoodInputV2("標準材料", aliases = listOf("材料"), baseUnit = "kg", id = food.id, expectedRevision = food.revision))
            assertTrue(shopping.week(start).needsRegeneration)
            generated = shopping.generate(start).data.items.single()
            assertEquals("g", generated.unit); assertEquals("1", generated.amount); assertTrue(generated.checked)
        }
    }

    @Test fun disappearedMaterialsAreRemovedAndAllUnneededDaysProduceAnEmptyUpToDateList() = runBlocking {
        withDatabase { db ->
            val foods = FoodRepositoryV2(db)
            val first = foods.save(FoodInputV2("材料A")); val second = foods.save(FoodInputV2("材料B"))
            val dish = recipe(db, "料理", first.id, "1")
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            val list = shopping.generate(start).data.list
            changeIngredient(db, dish.id, second.id, "2", "g")
            assertEquals(second.id, shopping.generate(start).data.items.single().foodMasterId)
            for (offset in 0L..6L) saveDay(db, start.plusDays(offset), null)
            val empty = shopping.generate(start)
            assertTrue(empty.data.items.isEmpty()); assertTrue(empty.warnings.isEmpty())
            assertEquals(list.id, empty.data.list.id); assertEquals(list.createdAt, empty.data.list.createdAt)
            assertFalse(shopping.week(start).needsRegeneration)
        }
    }

    @Test fun generationFailureAfterDeletingRowsRollsBackExistingListAndPurchaseStates() = runBlocking {
        withDatabase { db ->
            val food = FoodRepositoryV2(db).save(FoodInputV2("材料"))
            val dish = recipe(db, "料理", food.id, "1")
            saveDay(db, start, dish.id)
            val shopping = ShoppingRepositoryV2(db)
            shopping.updateState(shopping.generate(start).data.items.single(), true, true)
            changeIngredient(db, dish.id, food.id, "2", "g")
            val before = shopping.week(start).data!!
            db.openHelper.writableDatabase.execSQL("""CREATE TEMP TRIGGER reject_shopping BEFORE INSERT ON shoppingItems
                BEGIN SELECT RAISE(ABORT,'injected failure'); END""")
            val failure = runCatching { shopping.generate(start) }.exceptionOrNull()
            assertNotNull(failure); assertTrue(failure.toString().contains("injected failure"))
            assertEquals(before, shopping.week(start).data)
        }
    }

    @Test fun staleCheckWritesAndGenerationWithoutSavedMenuAreRejected() = runBlocking {
        withDatabase { db ->
            val shopping = ShoppingRepositoryV2(db)
            reject { shopping.generate(start) }
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM shoppingLists"))
            val dish = recipe(db, "料理", null, "1")
            saveDay(db, start, dish.id)
            val item = shopping.generate(start).data.items.single()
            val updated = shopping.updateState(item, checked = true)
            reject { shopping.updateState(item, excluded = true) }
            assertEquals(updated, shopping.week(start).data!!.items.single())
        }
    }

    private suspend fun recipe(db: RecipeManagerDatabaseV2, name: String, food: String?, quantity: String?, unit: String = "g") =
        RecipeRepositoryV2(db).save(RecipeInputV2(name, "ONE_DISH", listOf(RecipeIngredientInputV2("材料", quantity?.let(::BigDecimal), unit, foodMasterId = food))))
    private suspend fun changeIngredient(db: RecipeManagerDatabaseV2, id: String, food: String?, quantity: String?, unit: String) {
        val repository = RecipeRepositoryV2(db); val old = repository.find(id)!!
        repository.save(RecipeInputV2(old.recipe.name, "ONE_DISH", listOf(RecipeIngredientInputV2("材料", quantity?.let(::BigDecimal), unit, foodMasterId = food)), id, old.recipe.revision))
    }
    private suspend fun saveDay(db: RecipeManagerDatabaseV2, date: LocalDate, recipe: String?): MenuDayDetailsV2 {
        val menus = MenuRepositoryV2(db); val week = menus.week(date); val day = week.days.single { it.day.date == date.toString() }.day
        return menus.saveDay(MenuDayInputV2(date, recipe == null, recipe?.let { listOf(MenuSelectionV2(it, "ONE_DISH")) }.orEmpty()),
            week.menu?.revision, day.revision.takeIf { day.id.isNotEmpty() })
    }
    private suspend fun withDatabase(block: suspend (RecipeManagerDatabaseV2) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeManagerDatabaseV2::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private suspend fun reject(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("入力・競合の検証で拒否する必要があります: $error", error is IllegalArgumentException || error is IllegalStateException)
    }
    private fun scalar(db: RecipeManagerDatabaseV2, sql: String) = db.openHelper.writableDatabase.query(sql).use { check(it.moveToFirst()); it.getString(0) }
    private fun assertForeignKeys(db: RecipeManagerDatabaseV2) = db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
}
