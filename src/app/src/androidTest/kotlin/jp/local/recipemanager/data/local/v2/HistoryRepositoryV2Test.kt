package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryRepositoryV2Test {
    private lateinit var database: RecipeManagerDatabaseV2
    private lateinit var history: HistoryRepositoryV2
    private lateinit var main: RecipeEntityV2
    private lateinit var side: RecipeEntityV2
    private lateinit var soup: RecipeEntityV2
    private lateinit var one: RecipeEntityV2
    private lateinit var otherOne: RecipeEntityV2
    private val clock = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), RecipeManagerDatabaseV2::class.java).build()
        history = HistoryRepositoryV2(database, clock)
        val recipes = RecipeRepositoryV2(database, clock)
        suspend fun recipe(name: String, category: String) = recipes.save(RecipeInputV2(name, category,
            listOf(RecipeIngredientInputV2("水", BigDecimal("1.25"), "ml"))))
        main = recipe("主菜", "MAIN")
        side = recipe("副菜", "SIDE")
        soup = recipe("汁物", "SOUP")
        one = recipe("一品料理", "ONE_DISH")
        otherOne = recipe("別の一品料理", "ONE_DISH")
    }

    @After
    fun close() = database.close()

    @Test
    fun manualBackdatedHistoryUsesActualMaximumAndReusesLegacyDateId() = runBlocking {
        database.openHelper.writableDatabase.execSQL("INSERT INTO cookingHistories VALUES ('history-2026-11-09', '2026-11-09', ?, ?)", arrayOf(timestamp, timestamp))
        val legacy = history.recordManual(date("2026-11-09"), main.id)
        assertEquals("history-2026-11-09", legacy.historyId)
        val latest = history.recordManual(date("2026-11-12"), main.id)
        UUID.fromString(latest.historyId)
        UUID.fromString(latest.id)
        history.recordManual(date("2026-11-01"), main.id)
        assertEquals("2026-11-12", lastCooked(main.id))
        assertEquals("3", scalar("SELECT COUNT(*) FROM historyItems WHERE recipeId = ?", main.id))
        rejected { history.recordManual(date("2026-11-09"), main.id) }
        assertEquals("3", scalar("SELECT COUNT(*) FROM historyItems WHERE recipeId = ?", main.id))
        assertEquals("1", scalar("SELECT revision FROM recipes WHERE id = ?", main.id))
        assertNotNull(database.historyDao().header("history-2026-11-09"))
    }

    @Test
    fun partialCookingRecordsOnlyActualDishesAndPreservesShoppingExactly() = runBlocking {
        val day = threeDishDay()
        seedShopping()
        val shopping = shoppingSnapshot()
        val weekRevision = database.historyDao().week(day.day.weeklyMenuId)!!.revision
        val mainItem = day.items.single { it.role == "MAIN" }
        val sideItem = day.items.single { it.role == "SIDE" }
        val outcomes = history.recordCooking(day.day.id, day.day.revision, mapOf(mainItem.id to main.id, sideItem.id to otherOne.id))
        assertEquals(setOf("cooked", "changed", "not_cooked"), outcomes.map { it.state }.toSet())
        assertEquals("2", scalar("SELECT COUNT(*) FROM historyItems"))
        assertEquals("2026-11-09", lastCooked(main.id))
        assertEquals("2026-11-09", lastCooked(otherOne.id))
        assertNull(lastCooked(side.id))
        assertNull(lastCooked(soup.id))
        assertEquals(day.day.revision + 1, database.historyDao().day(day.day.id)!!.revision)
        assertEquals(weekRevision + 1, database.historyDao().week(day.day.weeklyMenuId)!!.revision)
        assertEquals(shopping, shoppingSnapshot())
        assertEquals("2", scalar("SELECT COUNT(*) FROM planOutcomes WHERE historyItemId IS NOT NULL"))
    }

    @Test
    fun allNotCookedCreatesNoHistoryAndNoLastCookedOrShoppingChanges() = runBlocking {
        val day = threeDishDay()
        seedShopping()
        val shopping = shoppingSnapshot()
        val outcomes = history.recordCooking(day.day.id, day.day.revision, emptyMap())
        assertTrue(outcomes.all { it.state == "not_cooked" && it.historyItemId == null })
        assertEquals("0", scalar("SELECT COUNT(*) FROM cookingHistories"))
        assertEquals("0", scalar("SELECT COUNT(*) FROM historyItems"))
        assertNull(lastCooked(main.id))
        assertEquals(shopping, shoppingSnapshot())
        rejected { history.recordCooking(day.day.id, database.historyDao().day(day.day.id)!!.revision, emptyMap()) }
        assertEquals("3", scalar("SELECT COUNT(*) FROM planOutcomes"))
    }

    @Test
    fun manualAndPlannedDuplicateOrStaleAndForeignInputFailAtomically() = runBlocking {
        val day = threeDishDay()
        val manual = history.recordManual(date("2026-11-09"), main.id)
        val beforeHeader = database.historyDao().header(manual.historyId)
        val mainItem = day.items.single { it.role == "MAIN" }
        rejected { history.recordCooking(day.day.id, day.day.revision, mapOf(mainItem.id to main.id)) }
        rejected { history.recordCooking(day.day.id, day.day.revision - 1, emptyMap()) }
        rejected { history.recordCooking(day.day.id, day.day.revision, mapOf("not-an-item-of-this-day" to otherOne.id)) }
        rejected { history.recordCooking(day.day.id, day.day.revision, mapOf(day.items[0].id to otherOne.id, day.items[1].id to otherOne.id)) }
        assertEquals("0", scalar("SELECT COUNT(*) FROM planOutcomes"))
        assertEquals("1", scalar("SELECT COUNT(*) FROM historyItems"))
        assertEquals(beforeHeader, database.historyDao().header(manual.historyId))
        assertEquals(day.day.revision, database.historyDao().day(day.day.id)!!.revision)
    }

    @Test
    fun historyEditAndDeleteRequireConfirmationDetachAndFreshTimestamp() = runBlocking {
        val day = oneDishDay(date("2026-11-09"), one)
        seedShopping()
        val shopping = shoppingSnapshot()
        val outcome = history.recordCooking(day.day.id, day.day.revision, mapOf(day.items.single().id to one.id)).single()
        val item = database.historyDao().item(outcome.historyItemId!!)!!
        val originalHeader = database.historyDao().header(item.historyId)!!
        rejected { history.editItem(item.id, originalHeader.updatedAt, date("2026-11-02"), one.id, "ONE_DISH", confirmed = false, detachLinkedOutcomes = true) }
        rejected { history.editItem(item.id, originalHeader.updatedAt, date("2026-11-02"), one.id, "ONE_DISH", confirmed = true) }
        assertNotNull(database.historyDao().outcome(day.items.single().id))
        assertEquals("2026-11-09", lastCooked(one.id))
        val edited = history.editItem(item.id, originalHeader.updatedAt, date("2026-11-02"), one.id, "ONE_DISH", confirmed = true, detachLinkedOutcomes = true)
        assertEquals(item.id, edited.id)
        assertNotEquals(item.historyId, edited.historyId)
        assertNull(database.historyDao().header(originalHeader.id))
        assertNull(database.historyDao().outcome(day.items.single().id))
        assertEquals("2026-11-02", lastCooked(one.id))
        val currentHeader = database.historyDao().header(edited.historyId)!!
        rejected { history.deleteItem(edited.id, currentHeader.updatedAt, confirmed = false) }
        rejected { history.deleteItem(edited.id, "stale", confirmed = true) }
        history.deleteItem(edited.id, currentHeader.updatedAt, confirmed = true)
        assertNull(lastCooked(one.id))
        assertEquals("0", scalar("SELECT COUNT(*) FROM cookingHistories"))
        assertEquals(shopping, shoppingSnapshot())
    }

    @Test
    fun pendingPostponementAndCancelNeverOverwriteTargetOrHistoryOrShopping() = runBlocking {
        val source = oneDishDay(date("2026-11-09"), one)
        val target = oneDishDay(date("2026-11-10"), otherOne)
        seedShopping()
        val shopping = shoppingSnapshot()
        val pending = history.postpone(source.items.single().id, date("2026-11-10"), source.day.revision)
        assertEquals("pending", pending.state)
        assertNull(pending.targetMenuItemId)
        assertEquals(target.items, database.historyDao().menuItems(target.day.id))
        assertEquals("0", scalar("SELECT COUNT(*) FROM historyItems"))
        assertEquals(shopping, shoppingSnapshot())
        val sourceRevision = database.historyDao().day(source.day.id)!!.revision
        rejected { history.cancelPostponement(pending.id, pending.updatedAt, sourceRevision, confirmed = false) }
        assertNotNull(database.historyDao().postponement(pending.id))
        history.cancelPostponement(pending.id, pending.updatedAt, sourceRevision, confirmed = true)
        assertNull(database.historyDao().postponement(pending.id))
        assertNull(database.historyDao().outcome(source.items.single().id))
        assertEquals(target.items, database.historyDao().menuItems(target.day.id))
        assertEquals(shopping, shoppingSnapshot())
    }

    @Test
    fun confirmPostponementSavesCompleteTargetThenCancelPreservesActualTargetHistory() = runBlocking {
        val source = oneDishDay(date("2026-11-09"), one)
        val target = oneDishDay(date("2026-11-10"), otherOne)
        val pending = history.postpone(source.items.single().id, date("2026-11-10"), source.day.revision)
        val targetWeek = database.historyDao().week(target.day.weeklyMenuId)!!
        val confirmed = history.confirmPostponement(pending.id, pending.updatedAt,
            database.historyDao().day(source.day.id)!!.revision,
            MenuDayInputV2(date("2026-11-10"), false, listOf(MenuSelectionV2(one.id, "ONE_DISH"))),
            targetWeek.revision, target.day.revision, confirmed = true)
        assertEquals("confirmed", confirmed.state)
        val newTarget = database.historyDao().menuItem(confirmed.targetMenuItemId!!)!!
        assertEquals(one.id, newTarget.recipeId)
        assertNotEquals(source.items.single().id, newTarget.id)
        assertEquals("0", scalar("SELECT COUNT(*) FROM historyItems"))
        history.recordCooking(newTarget.menuDayId, database.historyDao().day(newTarget.menuDayId)!!.revision, mapOf(newTarget.id to one.id))
        history.cancelPostponement(confirmed.id, confirmed.updatedAt, database.historyDao().day(source.day.id)!!.revision, confirmed = true)
        assertNull(database.historyDao().postponement(confirmed.id))
        assertNull(database.historyDao().outcome(source.items.single().id))
        assertEquals("cooked", database.historyDao().outcome(newTarget.id)!!.state)
        assertNotNull(database.historyDao().menuItem(newTarget.id))
        assertEquals("1", scalar("SELECT COUNT(*) FROM historyItems"))
        assertEquals("2026-11-10", lastCooked(one.id))
    }

    @Test
    fun confirmationCannotSilentlyUnlockDetachResultOrAcceptStalePostponement() = runBlocking {
        val source = oneDishDay(date("2026-11-09"), one)
        val target = oneDishDay(date("2026-11-10"), otherOne)
        val pending = history.postpone(source.items.single().id, date("2026-11-10"), source.day.revision)
        val input = MenuDayInputV2(date("2026-11-10"), false, listOf(MenuSelectionV2(one.id, "ONE_DISH")))
        suspend fun confirm(expectedAt: String = pending.updatedAt) = history.confirmPostponement(pending.id, expectedAt,
            database.historyDao().day(source.day.id)!!.revision, input,
            database.historyDao().week(target.day.weeklyMenuId)!!.revision,
            database.historyDao().day(target.day.id)!!.revision, confirmed = true)
        database.openHelper.writableDatabase.execSQL("UPDATE menuItems SET locked = 1 WHERE id = ?", arrayOf(target.items.single().id))
        rejected { confirm() }
        assertEquals(otherOne.id, database.historyDao().menuItems(target.day.id).single().recipeId)
        database.openHelper.writableDatabase.execSQL("UPDATE menuItems SET locked = 0 WHERE id = ?", arrayOf(target.items.single().id))
        history.recordCooking(target.day.id, database.historyDao().day(target.day.id)!!.revision, emptyMap())
        rejected { confirm() }
        assertEquals("not_cooked", database.historyDao().outcome(target.items.single().id)!!.state)
        history.clearOutcome(target.items.single().id, database.historyDao().day(target.day.id)!!.revision, confirmed = true)
        rejected { confirm("stale") }
        assertEquals("pending", database.historyDao().postponement(pending.id)!!.state)
        assertEquals(otherOne.id, database.historyDao().menuItems(target.day.id).single().recipeId)
        assertEquals("0", scalar("SELECT COUNT(*) FROM historyItems"))
        assertEquals("postponed", database.historyDao().outcome(source.items.single().id)!!.state)
    }

    private suspend fun threeDishDay() = day(date("2026-11-09"), listOf(MenuSelectionV2(main.id, "MAIN"), MenuSelectionV2(side.id, "SIDE"), MenuSelectionV2(soup.id, "SOUP")))
    private suspend fun oneDishDay(date: LocalDate, recipe: RecipeEntityV2) = day(date, listOf(MenuSelectionV2(recipe.id, "ONE_DISH")))
    private suspend fun day(date: LocalDate, selections: List<MenuSelectionV2>): MenuDayDetailsV2 {
        val menus = MenuRepositoryV2(database)
        val start = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return menus.saveDay(MenuDayInputV2(date, false, selections), menus.week(start).menu?.revision,
            database.historyDao().dayForDate(date.toString())?.revision)
    }

    private fun seedShopping() {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO shoppingLists VALUES ('shopping-fixture', '2026-11-09', ?, ?, 'fingerprint', 0)", arrayOf(timestamp, timestamp))
        db.execSQL("""INSERT INTO shoppingItems VALUES ('shopping-item', 'shopping-fixture', '水', '水', '1.2500', 'ml', ?, 1, 1, NULL, '["name","水","ml"]', 0)""",
            arrayOf("[{\"recipeId\":\"${main.id}\",\"recipeNameSnapshot\":\"以前の料理名\"}]"))
    }

    private fun shoppingSnapshot() = listOf("shoppingLists", "shoppingItems").flatMap { table ->
        database.openHelper.writableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
            buildList { while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        }
    }

    private fun lastCooked(recipeId: String) = scalar("SELECT lastCookedAt FROM recipes WHERE id = ?", recipeId)
    private fun scalar(sql: String, vararg args: Any?): String? = database.openHelper.writableDatabase.query(sql, args).use {
        if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Operation should have been rejected") } catch (_: Exception) { }
    }

    companion object {
        private const val timestamp = "2026-11-09T00:00:00Z"
        private fun date(text: String): LocalDate = LocalDate.parse(text)
    }
}
