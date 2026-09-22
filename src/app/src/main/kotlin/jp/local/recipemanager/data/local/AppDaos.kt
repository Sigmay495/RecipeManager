package jp.local.recipemanager.data.local

import androidx.room.*
import jp.local.recipemanager.domain.model.MenuRole
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate

data class MenuDayWithItems(@Embedded val day: MenuDayEntity, @Relation(parentColumn = "id", entityColumn = "menuDayId") val items: List<MenuItemEntity>)
data class WeeklyMenuWithDays(@Embedded val menu: WeeklyMenuEntity, @Relation(entity = MenuDayEntity::class, parentColumn = "id", entityColumn = "weeklyMenuId") val days: List<MenuDayWithItems>)
data class HistoryWithItems(@Embedded val history: CookingHistoryEntity, @Relation(parentColumn = "id", entityColumn = "historyId") val items: List<HistoryItemEntity>)
data class ShoppingListWithItems(@Embedded val list: ShoppingListEntity, @Relation(parentColumn = "id", entityColumn = "shoppingListId") val items: List<ShoppingItemEntity>)

@Dao
abstract class MenuDao {
    @Transaction @Query("SELECT * FROM weeklyMenus WHERE weekStart = :weekStart") abstract fun observeWeek(weekStart: LocalDate): Flow<WeeklyMenuWithDays?>
    @Transaction @Query("SELECT * FROM weeklyMenus WHERE weekStart = :weekStart") abstract suspend fun findByWeek(weekStart: LocalDate): WeeklyMenuWithDays?
    @Transaction @Query("SELECT * FROM menuDays WHERE date = :date") abstract suspend fun findDay(date: LocalDate): MenuDayWithItems?
    @Query("SELECT * FROM menuDays WHERE noMeal = 1 ORDER BY date DESC") abstract fun observeNoMealDays(): Flow<List<MenuDayEntity>>
    @Upsert protected abstract suspend fun upsertMenu(menu: WeeklyMenuEntity)
    @Upsert protected abstract suspend fun upsertDay(day: MenuDayEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertItems(items: List<MenuItemEntity>)
    @Query("DELETE FROM menuItems WHERE menuDayId = :dayId") protected abstract suspend fun deleteItems(dayId: String)
    @Query("DELETE FROM menuDays WHERE id = :dayId") protected abstract suspend fun deleteDay(dayId: String)

    @Transaction
    open suspend fun saveDay(menu: WeeklyMenuEntity, day: MenuDayEntity, items: List<MenuItemEntity>) {
        require(day.weeklyMenuId == menu.id && items.all { it.menuDayId == day.id })
        validateDay(day, items)
        upsertMenu(menu)
        findDay(day.date)?.takeIf { it.day.id != day.id }?.let { deleteItems(it.day.id); deleteDay(it.day.id) }
        deleteItems(day.id)
        upsertDay(day)
        if (items.isNotEmpty()) insertItems(items)
    }

    @Transaction
    open suspend fun saveDays(menu: WeeklyMenuEntity, days: List<MenuDayEntity>, items: List<MenuItemEntity>) {
        require(days.all { it.weeklyMenuId == menu.id })
        days.forEach { day -> validateDay(day, items.filter { it.menuDayId == day.id }) }
        upsertMenu(menu)
        days.forEach { day ->
            findDay(day.date)?.takeIf { it.day.id != day.id }?.let { deleteItems(it.day.id); deleteDay(it.day.id) }
            deleteItems(day.id)
            upsertDay(day)
            val dayItems = items.filter { it.menuDayId == day.id }
            if (dayItems.isNotEmpty()) insertItems(dayItems)
        }
    }

    @Transaction
    open suspend fun deleteNoMealDay(date: LocalDate) {
        val record = findDay(date) ?: return
        require(record.day.noMeal)
        deleteItems(record.day.id)
        deleteDay(record.day.id)
    }

    companion object {
        fun validateDay(day: MenuDayEntity, items: List<MenuItemEntity>) {
            if (day.noMeal) require(items.isEmpty()) { "ご飯不要日には料理を登録できません" }
            else {
                val roles = items.map { it.role }
                require(roles == listOf(MenuRole.ONE_DISH) || (roles.size == 3 && roles.toSet() == setOf(MenuRole.MAIN, MenuRole.SIDE, MenuRole.SOUP))) {
                    "献立は一品もの、または主菜・副菜・汁物で登録してください"
                }
            }
        }
    }
}

@Dao
abstract class HistoryDao {
    @Transaction @Query("SELECT * FROM cookingHistories ORDER BY cookedDate DESC") abstract fun observeAll(): Flow<List<HistoryWithItems>>
    @Transaction @Query("SELECT * FROM cookingHistories ORDER BY cookedDate DESC") abstract suspend fun getAll(): List<HistoryWithItems>
    @Transaction @Query("SELECT * FROM cookingHistories WHERE id = :id") abstract suspend fun findById(id: String): HistoryWithItems?
    @Query("SELECT * FROM cookingHistories WHERE cookedDate = :date") protected abstract suspend fun findHeaderByDate(date: LocalDate): CookingHistoryEntity?
    @Upsert protected abstract suspend fun upsertHistory(history: CookingHistoryEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertItems(items: List<HistoryItemEntity>)
    @Query("DELETE FROM historyItems WHERE historyId = :historyId") protected abstract suspend fun deleteItems(historyId: String)
    @Query("DELETE FROM cookingHistories WHERE id = :historyId") protected abstract suspend fun deleteHeader(historyId: String)
    @Query("SELECT DISTINCT recipeId FROM historyItems WHERE historyId = :historyId") protected abstract suspend fun recipeIds(historyId: String): List<String>
    @Query("""UPDATE recipes SET lastCookedAt = COALESCE((SELECT MAX(h.cookedDate) FROM cookingHistories h JOIN historyItems i ON i.historyId = h.id WHERE i.recipeId = recipes.id), initialLastCookedAt), updatedAt = :updatedAt WHERE id IN (:recipeIds)""")
    protected abstract suspend fun recalculate(recipeIds: List<String>, updatedAt: Instant)

    @Transaction
    open suspend fun save(history: CookingHistoryEntity, items: List<HistoryItemEntity>) {
        require(items.isNotEmpty() && items.all { it.historyId == history.id })
        val duplicate = findHeaderByDate(history.cookedDate)
        require(duplicate == null || duplicate.id == history.id) { "この日付の調理履歴は登録済みです" }
        val affected = (findById(history.id)?.items.orEmpty().map { it.recipeId } + items.map { it.recipeId }).distinct()
        upsertHistory(history); deleteItems(history.id); insertItems(items); recalculate(affected, history.updatedAt)
    }

    @Transaction
    open suspend fun delete(id: String, updatedAt: Instant) {
        val affected = recipeIds(id)
        deleteItems(id); deleteHeader(id)
        if (affected.isNotEmpty()) recalculate(affected, updatedAt)
    }
}

@Dao abstract class ShoppingDao {
    @Transaction @Query("SELECT * FROM shoppingLists WHERE weekStart = :weekStart") abstract fun observeWeek(weekStart: LocalDate): Flow<ShoppingListWithItems?>
    @Transaction @Query("SELECT * FROM shoppingLists WHERE weekStart = :weekStart") abstract suspend fun findWeek(weekStart: LocalDate): ShoppingListWithItems?
    @Upsert protected abstract suspend fun upsertList(list: ShoppingListEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) protected abstract suspend fun insertItems(items: List<ShoppingItemEntity>)
    @Query("DELETE FROM shoppingItems WHERE shoppingListId = :listId") protected abstract suspend fun deleteItems(listId: String)
    @Update abstract suspend fun updateItem(item: ShoppingItemEntity)

    @Transaction open suspend fun replace(list: ShoppingListEntity, items: List<ShoppingItemEntity>) {
        require(items.all { it.shoppingListId == list.id })
        upsertList(list)
        deleteItems(list.id)
        if (items.isNotEmpty()) insertItems(items)
    }
}

@Dao interface SystemDataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSetting(setting: SettingEntity)
    @Query("SELECT value FROM settings WHERE `key` = :key") suspend fun getSetting(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(metadata: MetadataEntity)
    @Query("SELECT value FROM metadata WHERE `key` = :key") suspend fun getMetadata(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHolidays(holidays: List<HolidayEntity>)
    @Query("SELECT * FROM holidays WHERE year = :year ORDER BY date") suspend fun holidays(year: Int): List<HolidayEntity>
    @Query("SELECT * FROM holidays WHERE date BETWEEN :from AND :to ORDER BY date") fun observeHolidays(from: LocalDate, to: LocalDate): Flow<List<HolidayEntity>>
    @Query("SELECT * FROM holidays WHERE date BETWEEN :from AND :to ORDER BY date") suspend fun holidaysBetween(from: LocalDate, to: LocalDate): List<HolidayEntity>
}
