package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update

data class HistoryDetailsV2(
    @Embedded val history: CookingHistoryEntityV2,
    @Relation(parentColumn = "id", entityColumn = "historyId") val items: List<HistoryItemEntityV2>,
)

/** Persistence primitives for actual cooking and the separate results of planned dishes. */
@Dao
abstract class HistoryDaoV2 {
    @Transaction
    @Query("SELECT * FROM cookingHistories ORDER BY cookedDate DESC, id")
    abstract suspend fun all(): List<HistoryDetailsV2>

    @Query("SELECT * FROM cookingHistories WHERE id = :id")
    abstract suspend fun header(id: String): CookingHistoryEntityV2?

    @Query("SELECT * FROM cookingHistories WHERE cookedDate = :date")
    abstract suspend fun headerForDate(date: String): CookingHistoryEntityV2?

    @Query("SELECT * FROM historyItems WHERE id = :id")
    abstract suspend fun item(id: String): HistoryItemEntityV2?

    @Query("SELECT * FROM historyItems WHERE historyId = :historyId")
    abstract suspend fun items(historyId: String): List<HistoryItemEntityV2>

    @Query("""SELECT COUNT(*) FROM historyItems i JOIN cookingHistories h ON h.id = i.historyId
        WHERE h.cookedDate = :date AND i.recipeId = :recipeId AND i.id != :excludedItemId""")
    abstract suspend fun duplicateCount(date: String, recipeId: String, excludedItemId: String = ""): Int

    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun recipe(id: String): RecipeEntityV2?

    @Query("SELECT * FROM menuDays WHERE id = :id")
    abstract suspend fun day(id: String): MenuDayEntityV2?

    @Query("SELECT * FROM menuDays WHERE date = :date")
    abstract suspend fun dayForDate(date: String): MenuDayEntityV2?

    @Query("SELECT * FROM weeklyMenus WHERE id = :id")
    abstract suspend fun week(id: String): WeeklyMenuEntityV2?

    @Query("SELECT * FROM menuItems WHERE id = :id")
    abstract suspend fun menuItem(id: String): MenuItemEntityV2?

    @Query("SELECT * FROM menuItems WHERE menuDayId = :dayId ORDER BY role, id")
    abstract suspend fun menuItems(dayId: String): List<MenuItemEntityV2>

    @Query("SELECT * FROM planOutcomes WHERE menuItemId = :menuItemId")
    abstract suspend fun outcome(menuItemId: String): PlanOutcomeEntityV2?

    @Query("SELECT * FROM planOutcomes WHERE historyItemId = :itemId")
    abstract suspend fun outcomesForHistory(itemId: String): List<PlanOutcomeEntityV2>

    @Query("SELECT * FROM postponedPlans WHERE id = :id")
    abstract suspend fun postponement(id: String): PostponedPlanEntityV2?

    @Query("SELECT * FROM postponedPlans WHERE sourceMenuItemId = :menuItemId OR targetMenuItemId = :menuItemId")
    abstract suspend fun postponementsForItem(menuItemId: String): List<PostponedPlanEntityV2>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertHeader(header: CookingHistoryEntityV2)

    @Update
    abstract suspend fun updateHeader(header: CookingHistoryEntityV2): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertItem(item: HistoryItemEntityV2)

    @Update
    abstract suspend fun updateItem(item: HistoryItemEntityV2): Int

    @Query("DELETE FROM historyItems WHERE id = :id")
    abstract suspend fun deleteItem(id: String): Int

    @Query("DELETE FROM cookingHistories WHERE id = :id")
    abstract suspend fun deleteHeader(id: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertOutcome(outcome: PlanOutcomeEntityV2)

    @Query("DELETE FROM planOutcomes WHERE menuItemId = :menuItemId")
    abstract suspend fun deleteOutcome(menuItemId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertPostponement(postponement: PostponedPlanEntityV2)

    @Update
    abstract suspend fun updatePostponement(postponement: PostponedPlanEntityV2): Int

    @Query("DELETE FROM postponedPlans WHERE id = :id")
    abstract suspend fun deletePostponement(id: String): Int

    @Query("UPDATE menuDays SET revision = revision + 1 WHERE id = :id")
    abstract suspend fun bumpDay(id: String): Int

    @Query("UPDATE weeklyMenus SET revision = revision + 1, updatedAt = :now WHERE id = :id")
    abstract suspend fun bumpWeek(id: String, now: String): Int

    @Query("""UPDATE recipes SET lastCookedAt = (SELECT MAX(h.cookedDate) FROM cookingHistories h
        JOIN historyItems i ON i.historyId = h.id WHERE i.recipeId = recipes.id) WHERE id = :recipeId""")
    abstract suspend fun recalculateLastCooked(recipeId: String)
}
