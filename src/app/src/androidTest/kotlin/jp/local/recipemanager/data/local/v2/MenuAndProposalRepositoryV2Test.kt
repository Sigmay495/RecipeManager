package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class MenuAndProposalRepositoryV2Test {
    private val start = LocalDate.parse("2026-11-23")
    private val fixedClock = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneId.of("Asia/Tokyo"))

    @Test fun initialWeekIsReadOnlyAndHolidayWeekendDefaultsDoNotOverrideExplicitDays() = runBlocking {
        withDatabase { db ->
            sql(db, "INSERT INTO holidays VALUES ('2026-11-23','休日',2026)")
            val menus = MenuRepositoryV2(db, fixedClock)
            val initial = menus.week(start)
            assertNull(initial.menu)
            assertEquals(7, initial.days.size)
            assertEquals(3, initial.days.count { it.day.noMeal })
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuDays"))
            assertTrue(2026 in initial.coveredHolidayYears)
            val saturday = start.plusDays(5)
            menus.saveDay(MenuDayInputV2(saturday, false), null, null)
            val saved = menus.week(start).days.single { it.day.date == saturday.toString() }
            assertFalse(saved.day.noMeal)
            assertEquals("explicit", saved.day.requirementOrigin)
            assertTrue(saved.items.isEmpty())
        }
    }

    @Test fun manualSavesEnforceStructureCategoryAndOptimisticRevisionsAtomically() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val recipe = recipe(db, "料理")
            val empty = menus.saveDay(MenuDayInputV2(start, false), null, null)
            var week = menus.week(start)
            reject { menus.saveDay(MenuDayInputV2(start, false, listOf(MenuSelectionV2(recipe.id, "MAIN"))), week.menu!!.revision, empty.day.revision) }
            assertTrue(menus.week(start).days.first().items.isEmpty())
            val saved = menus.saveDay(one(start, recipe.id), week.menu!!.revision, empty.day.revision)
            week = menus.week(start)
            reject { menus.saveDay(MenuDayInputV2(start, true), week.menu!!.revision - 1, saved.day.revision) }
            reject { menus.saveDay(MenuDayInputV2(start, false, dayLocked = true), week.menu!!.revision, saved.day.revision) }
            assertEquals(saved, menus.week(start).days.first())
            assertForeignKeys(db)
        }
    }

    @Test fun fixedAndRetiredDishesAreRetainedButCannotBeNewlySelectedOrReplaced() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val first = recipe(db, "固定料理")
            val second = recipe(db, "別料理")
            var saved = menus.saveDay(one(start, first.id, locked = true), null, null)
            val inputs = RecipeRepositoryV2(db).find(first.id)!!
            RecipeRepositoryV2(db).save(RecipeInputV2(first.name, first.category,
                inputs.ingredients.map { RecipeIngredientInputV2(it.name, it.amount?.let(::BigDecimal), it.unit, id = it.id) },
                id = first.id, expectedRevision = first.revision, proposalPriority = "retired"))
            var week = menus.week(start)
            reject { menus.saveDay(one(start, second.id), week.menu!!.revision, saved.day.revision) }
            reject { menus.saveDay(one(start.plusDays(1), first.id), week.menu!!.revision, null) }
            saved = menus.saveDay(one(start, first.id), week.menu!!.revision, saved.day.revision)
            week = menus.week(start)
            saved = menus.saveDay(one(start, second.id), week.menu!!.revision, saved.day.revision)
            assertEquals(second.id, saved.items.single().recipeId)
        }
    }

    @Test fun unchangedSaveRetainsItemIdsRevisionAndSnapshots() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val dish = recipe(db, "料理")
            val before = menus.saveDay(one(start, dish.id), null, null)
            val week = menus.week(start).menu!!
            val after = menus.saveDay(one(start, dish.id), week.revision, before.day.revision)
            assertEquals(before, after)
            assertEquals(week, menus.week(start).menu)
        }
    }

    @Test fun immediateUndoRestoresDishesLocksAndLegacyNotesAndIsOneShot() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val dish = recipe(db, "料理")
            val before = menus.saveDay(one(start, dish.id, locked = true).copy(dayLocked = true, noMealReason = "旧理由", noMealMemo = "旧メモ"), null, null)
            val change = menus.setNoMealWithUndo(start, menus.week(start).menu!!.revision, before.day.revision)
            assertTrue(change.day.day.noMeal)
            val restored = menus.undoNoMeal(change.undoToken!!)
            assertEquals(before.items, restored.items)
            assertTrue(restored.day.dayLocked)
            assertEquals("旧理由", restored.day.noMealReason)
            assertEquals("旧メモ", restored.day.noMealMemo)
            reject { menus.undoNoMeal(change.undoToken) }
            val next = menus.setNoMealWithUndo(start, menus.week(start).menu!!.revision, restored.day.revision)
            menus.discardUndo(next.undoToken!!)
            reject { menus.undoNoMeal(next.undoToken) }
        }
    }

    @Test fun undoRejectsNewRelatedHistoryEvenWhenDayItselfWasNotChanged() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val dish = recipe(db, "料理")
            val saved = menus.saveDay(one(start, dish.id), null, null)
            val change = menus.setNoMealWithUndo(start, menus.week(start).menu!!.revision, saved.day.revision)
            HistoryRepositoryV2(db, fixedClock).recordManual(start, dish.id)
            reject { menus.undoNoMeal(change.undoToken!!) }
            assertTrue(menus.week(start).days.first().day.noMeal)
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM historyItems"))
        }
    }

    @Test fun removingRecordedDishesRequiresDetachAndUndoNeverRecreatesResultsOrDeletesHistory() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val dish = recipe(db, "料理")
            val saved = menus.saveDay(one(start, dish.id), null, null)
            HistoryRepositoryV2(db, fixedClock).recordCooking(saved.day.id, saved.day.revision, mapOf(saved.items.single().id to dish.id))
            val current = menus.week(start)
            reject { menus.setNoMealWithUndo(start, current.menu!!.revision, current.days.first().day.revision) }
            val change = menus.setNoMealWithUndo(start, current.menu!!.revision, current.days.first().day.revision, confirmDetach = true)
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM planOutcomes"))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM historyItems"))
            menus.undoNoMeal(change.undoToken!!)
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM planOutcomes"))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM historyItems"))
            assertEquals(start.toString(), RecipeRepositoryV2(db).find(dish.id)!!.recipe.lastCookedAt)
            assertForeignKeys(db)
        }
    }

    @Test fun previewWritesNothingAndAdoptionPersistsSevenDaysInOneTransaction() = runBlocking {
        withDatabase { db ->
            repeat(12) { recipe(db, "料理$it") }
            val proposals = MenuProposalRepositoryV2(db, fixedClock)
            val preview = proposals.preview(start, 42)
            assertTrue(preview.result is ProposalResultV2.Success)
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM weeklyMenus"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuItems"))
            val saved = proposals.adopt(preview)
            assertNotNull(saved.menu)
            assertEquals("7", scalar(db, "SELECT COUNT(*) FROM menuDays"))
            assertEquals("5", scalar(db, "SELECT COUNT(*) FROM menuItems"))
            assertForeignKeys(db)
        }
    }

    @Test fun confirmedPostponedTargetRequiresActualDetachBeforeRemovalAndPreservesHistory() = runBlocking {
        withDatabase { db ->
            val menus = MenuRepositoryV2(db, fixedClock)
            val histories = HistoryRepositoryV2(db, fixedClock)
            val dish = recipe(db, "延期料理")
            val source = menus.saveDay(one(start, dish.id), null, null)
            val pending = histories.postpone(source.items.single().id, start.plusDays(1), source.day.revision)
            var week = menus.week(start)
            histories.confirmPostponement(pending.id, pending.updatedAt, week.days.first().day.revision,
                one(start.plusDays(1), dish.id), week.menu!!.revision, null, confirmed = true)
            week = menus.week(start)
            val target = week.days[1]
            histories.recordCooking(target.day.id, target.day.revision, mapOf(target.items.single().id to dish.id))
            week = menus.week(start)
            reject { menus.saveDay(MenuDayInputV2(start.plusDays(1), true), week.menu!!.revision, week.days[1].day.revision, confirmDetach = true) }
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM postponedPlans"))
            assertEquals("2", scalar(db, "SELECT COUNT(*) FROM planOutcomes"))
            histories.clearOutcome(target.items.single().id, week.days[1].day.revision, confirmed = true)
            week = menus.week(start)
            menus.saveDay(MenuDayInputV2(start.plusDays(1), true), week.menu!!.revision, week.days[1].day.revision, confirmDetach = true)
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM postponedPlans"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM planOutcomes"))
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM historyItems"))
            assertEquals(start.plusDays(1).toString(), RecipeRepositoryV2(db).find(dish.id)!!.recipe.lastCookedAt)
            assertForeignKeys(db)
        }
    }

    @Test fun adoptionRejectsChangedSourcesAndDateRolloverWithoutAnyWrites() = runBlocking {
        withDatabase { db ->
            repeat(10) { recipe(db, "料理$it") }
            val clock = MovingClock(Instant.parse("2026-11-23T01:00:00Z"))
            val proposals = MenuProposalRepositoryV2(db, clock)
            val preview = proposals.preview(start, 17)
            recipe(db, "追加料理")
            reject { proposals.adopt(preview) }
            val next = proposals.preview(start, 17)
            clock.value = clock.value.plusSeconds(86400)
            reject { proposals.adopt(next) }
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM weeklyMenus"))
        }
    }

    @Test fun proposalKeepsRetiredFixedAndRecordedDaysAndTheirStableReferences() = runBlocking {
        withDatabase { db ->
            val fixed = recipe(db, "固定")
            repeat(12) { recipe(db, "料理$it") }
            val menus = MenuRepositoryV2(db, fixedClock)
            val saved = menus.saveDay(one(start, fixed.id, locked = true), null, null)
            sql(db, "UPDATE recipes SET proposalPriority='retired',revision=revision+1 WHERE id=?", fixed.id)
            sql(db, "INSERT INTO planOutcomes VALUES ('result',?,'not_cooked',NULL,'2026-11-23T00:00:00Z')", saved.items.single().id)
            val proposals = MenuProposalRepositoryV2(db, fixedClock)
            val preview = proposals.preview(start, 99)
            assertTrue(preview.result is ProposalResultV2.Success)
            val adopted = proposals.adopt(preview)
            assertEquals(saved.items, adopted.days.first().items)
            assertEquals("1", scalar(db, "SELECT COUNT(*) FROM planOutcomes WHERE id='result'"))
        }
    }

    @Test fun lateAdoptionFailureRollsBackPreviouslySavedDaysAndWeek() = runBlocking {
        withDatabase { db ->
            repeat(10) { recipe(db, "料理$it") }
            val proposals = MenuProposalRepositoryV2(db, fixedClock)
            val preview = proposals.preview(start, 29)
            assertTrue(preview.result is ProposalResultV2.Success)
            sql(db, """CREATE TEMP TRIGGER reject_second_day BEFORE INSERT ON menuDays
                WHEN NEW.date='2026-11-24' BEGIN SELECT RAISE(ABORT, 'injected failure'); END""")
            val error = runCatching { proposals.adopt(preview) }.exceptionOrNull()
            assertNotNull(error)
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM weeklyMenus"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuDays"))
            assertEquals("0", scalar(db, "SELECT COUNT(*) FROM menuItems"))
        }
    }

    private suspend fun recipe(db: RecipeManagerDatabaseV2, name: String) = RecipeRepositoryV2(db).save(
        RecipeInputV2(name, "ONE_DISH", listOf(RecipeIngredientInputV2("材料", BigDecimal.ONE, "g"))))
    private fun one(date: LocalDate, recipe: String, locked: Boolean = false) = MenuDayInputV2(date, false, listOf(MenuSelectionV2(recipe, "ONE_DISH", locked)))
    private suspend fun withDatabase(block: suspend (RecipeManagerDatabaseV2) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeManagerDatabaseV2::class.java).build()
        try { block(db) } finally { db.close() }
    }
    private suspend fun reject(block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("検証で停止する必要があります: $error", error is IllegalArgumentException || error is IllegalStateException)
    }
    private fun scalar(db: RecipeManagerDatabaseV2, query: String): String? = db.openHelper.writableDatabase.query(query).use {
        check(it.moveToFirst()); if (it.isNull(0)) null else it.getString(0)
    }
    private fun sql(db: RecipeManagerDatabaseV2, statement: String, vararg args: Any?) = db.openHelper.writableDatabase.execSQL(statement, args)
    private fun assertForeignKeys(db: RecipeManagerDatabaseV2) = db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    private class MovingClock(var value: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("Asia/Tokyo")
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(value, zone)
        override fun instant(): Instant = value
    }
}
