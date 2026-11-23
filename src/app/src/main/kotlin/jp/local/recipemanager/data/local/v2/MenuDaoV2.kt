package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface MenuDaoV2 {
    @Query("SELECT * FROM weeklyMenus WHERE weekStart = :start") suspend fun week(start: String): WeeklyMenuEntityV2?
    @Query("SELECT * FROM weeklyMenus WHERE id = :id") suspend fun weekById(id: String): WeeklyMenuEntityV2?
    @Query("SELECT * FROM weeklyMenus ORDER BY id") suspend fun allWeeks(): List<WeeklyMenuEntityV2>
    @Query("SELECT * FROM menuDays WHERE date = :date") suspend fun day(date: String): MenuDayEntityV2?
    @Query("SELECT * FROM menuDays WHERE id = :id") suspend fun dayById(id: String): MenuDayEntityV2?
    @Query("SELECT * FROM menuDays WHERE weeklyMenuId = :id ORDER BY date") suspend fun days(id: String): List<MenuDayEntityV2>
    @Query("SELECT * FROM menuDays ORDER BY id") suspend fun allDays(): List<MenuDayEntityV2>
    @Query("SELECT * FROM menuItems WHERE menuDayId = :dayId ORDER BY role, id") suspend fun items(dayId: String): List<MenuItemEntityV2>
    @Query("SELECT * FROM menuItems ORDER BY id") suspend fun allItems(): List<MenuItemEntityV2>
    @Query("SELECT * FROM planOutcomes ORDER BY id") suspend fun outcomes(): List<PlanOutcomeEntityV2>
    @Query("SELECT * FROM postponedPlans ORDER BY id") suspend fun postponements(): List<PostponedPlanEntityV2>
    @Query("SELECT * FROM cookingHistories ORDER BY id") suspend fun histories(): List<CookingHistoryEntityV2>
    @Query("SELECT * FROM historyItems ORDER BY id") suspend fun historyItems(): List<HistoryItemEntityV2>
    @Query("SELECT * FROM recipes ORDER BY id") suspend fun recipes(): List<RecipeEntityV2>
    @Query("SELECT * FROM holidays WHERE date >= :start AND date <= :end ORDER BY date")
    suspend fun holidays(start: String, end: String): List<HolidayEntityV2>
    @Query("SELECT DISTINCT year FROM holidays ORDER BY year") suspend fun holidayYears(): List<Int>

    @Insert suspend fun insertWeek(week: WeeklyMenuEntityV2)
    @Update suspend fun updateWeek(week: WeeklyMenuEntityV2)
    @Insert suspend fun insertDay(day: MenuDayEntityV2)
    @Update suspend fun updateDay(day: MenuDayEntityV2)
    @Insert suspend fun insertItems(items: List<MenuItemEntityV2>)
    @Update suspend fun updateItems(items: List<MenuItemEntityV2>)
    @Query("DELETE FROM menuItems WHERE id IN (:ids)") suspend fun deleteItems(ids: List<String>)
    @Query("DELETE FROM planOutcomes WHERE id IN (:ids)") suspend fun deleteOutcomes(ids: List<String>)
    @Query("DELETE FROM postponedPlans WHERE id IN (:ids)") suspend fun deletePostponements(ids: List<String>)
    @Query("UPDATE shoppingLists SET needsRegeneration = 1 WHERE weekStart = :start")
    suspend fun invalidateShopping(start: String)
}
