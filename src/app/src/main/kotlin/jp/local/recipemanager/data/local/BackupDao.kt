package jp.local.recipemanager.data.local

import androidx.room.*
import java.time.Instant

data class BackupRows(
    val recipes: List<RecipeEntity>, val ingredients: List<RecipeIngredientEntity>,
    val menus: List<WeeklyMenuEntity>, val days: List<MenuDayEntity>, val menuItems: List<MenuItemEntity>,
    val histories: List<CookingHistoryEntity>, val historyItems: List<HistoryItemEntity>,
    val shoppingLists: List<ShoppingListEntity>, val shoppingItems: List<ShoppingItemEntity>,
    val userMasters: List<FoodMasterWithDetails>, val settings: List<SettingEntity>,
)

@Dao
abstract class BackupDao {
    @Query("SELECT * FROM recipes") abstract suspend fun recipes(): List<RecipeEntity>
    @Query("SELECT * FROM recipeIngredients") abstract suspend fun ingredients(): List<RecipeIngredientEntity>
    @Query("SELECT * FROM weeklyMenus") abstract suspend fun menus(): List<WeeklyMenuEntity>
    @Query("SELECT * FROM menuDays") abstract suspend fun days(): List<MenuDayEntity>
    @Query("SELECT * FROM menuItems") abstract suspend fun menuItems(): List<MenuItemEntity>
    @Query("SELECT * FROM cookingHistories") abstract suspend fun histories(): List<CookingHistoryEntity>
    @Query("SELECT * FROM historyItems") abstract suspend fun historyItems(): List<HistoryItemEntity>
    @Query("SELECT * FROM shoppingLists") abstract suspend fun shoppingLists(): List<ShoppingListEntity>
    @Query("SELECT * FROM shoppingItems") abstract suspend fun shoppingItems(): List<ShoppingItemEntity>
    @Transaction @Query("SELECT * FROM foodMasters WHERE userEditable = 1") abstract suspend fun userMasters(): List<FoodMasterWithDetails>
    @Query("SELECT * FROM settings") abstract suspend fun settings(): List<SettingEntity>

    suspend fun snapshot() = BackupRows(recipes(), ingredients(), menus(), days(), menuItems(), histories(), historyItems(), shoppingLists(), shoppingItems(), userMasters(), settings())

    @Query("DELETE FROM shoppingItems") protected abstract suspend fun clearShoppingItems()
    @Query("DELETE FROM shoppingLists") protected abstract suspend fun clearShoppingLists()
    @Query("DELETE FROM historyItems") protected abstract suspend fun clearHistoryItems()
    @Query("DELETE FROM cookingHistories") protected abstract suspend fun clearHistories()
    @Query("DELETE FROM menuItems") protected abstract suspend fun clearMenuItems()
    @Query("DELETE FROM menuDays") protected abstract suspend fun clearDays()
    @Query("DELETE FROM weeklyMenus") protected abstract suspend fun clearMenus()
    @Query("DELETE FROM recipeIngredients") protected abstract suspend fun clearIngredients()
    @Query("DELETE FROM recipes") protected abstract suspend fun clearRecipes()
    @Query("DELETE FROM foodMasterAliases WHERE foodMasterId IN (SELECT id FROM foodMasters WHERE userEditable = 1)") protected abstract suspend fun clearUserAliases()
    @Query("DELETE FROM foodMasterGroups WHERE foodMasterId IN (SELECT id FROM foodMasters WHERE userEditable = 1)") protected abstract suspend fun clearUserGroups()
    @Query("DELETE FROM foodMasterSeasons WHERE foodMasterId IN (SELECT id FROM foodMasters WHERE userEditable = 1)") protected abstract suspend fun clearUserSeasons()
    @Query("DELETE FROM foodMasters WHERE userEditable = 1") protected abstract suspend fun clearUserMasters()
    @Query("DELETE FROM settings") protected abstract suspend fun clearSettings()
    @Insert protected abstract suspend fun putRecipes(rows: List<RecipeEntity>)
    @Insert protected abstract suspend fun putIngredients(rows: List<RecipeIngredientEntity>)
    @Insert protected abstract suspend fun putMenus(rows: List<WeeklyMenuEntity>)
    @Insert protected abstract suspend fun putDays(rows: List<MenuDayEntity>)
    @Insert protected abstract suspend fun putMenuItems(rows: List<MenuItemEntity>)
    @Insert protected abstract suspend fun putHistories(rows: List<CookingHistoryEntity>)
    @Insert protected abstract suspend fun putHistoryItems(rows: List<HistoryItemEntity>)
    @Insert protected abstract suspend fun putShoppingLists(rows: List<ShoppingListEntity>)
    @Insert protected abstract suspend fun putShoppingItems(rows: List<ShoppingItemEntity>)
    @Insert protected abstract suspend fun putMasters(rows: List<FoodMasterEntity>)
    @Insert protected abstract suspend fun putAliases(rows: List<FoodMasterAliasEntity>)
    @Insert protected abstract suspend fun putGroups(rows: List<FoodMasterGroupEntity>)
    @Insert protected abstract suspend fun putSeasons(rows: List<FoodMasterSeasonEntity>)
    @Insert protected abstract suspend fun putSettings(rows: List<SettingEntity>)
    @Query("""UPDATE recipes SET lastCookedAt = COALESCE((SELECT MAX(h.cookedDate) FROM cookingHistories h JOIN historyItems i ON i.historyId = h.id WHERE i.recipeId = recipes.id), initialLastCookedAt), updatedAt = :now""") protected abstract suspend fun recalculate(now: Instant)

    @Transaction
    open suspend fun replace(rows: BackupRows, backupCreatedAt: Instant) {
        clearShoppingItems(); clearShoppingLists(); clearHistoryItems(); clearHistories(); clearMenuItems(); clearDays(); clearMenus(); clearIngredients(); clearRecipes()
        clearUserAliases(); clearUserGroups(); clearUserSeasons(); clearUserMasters(); clearSettings()
        putIfNotEmpty(rows.userMasters.map { it.master }, ::putMasters)
        putIfNotEmpty(rows.userMasters.flatMap { it.aliases }, ::putAliases)
        putIfNotEmpty(rows.userMasters.flatMap { it.groups }, ::putGroups)
        putIfNotEmpty(rows.userMasters.flatMap { it.seasons }, ::putSeasons)
        putIfNotEmpty(rows.recipes, ::putRecipes); putIfNotEmpty(rows.ingredients, ::putIngredients)
        putIfNotEmpty(rows.menus, ::putMenus); putIfNotEmpty(rows.days, ::putDays); putIfNotEmpty(rows.menuItems, ::putMenuItems)
        putIfNotEmpty(rows.histories, ::putHistories); putIfNotEmpty(rows.historyItems, ::putHistoryItems)
        putIfNotEmpty(rows.shoppingLists, ::putShoppingLists); putIfNotEmpty(rows.shoppingItems, ::putShoppingItems)
        putSettings(rows.settings.filterNot { it.key == "lastBackupAt" } + SettingEntity("lastBackupAt", backupCreatedAt.toString()))
        recalculate(Instant.now())
    }

    private suspend fun <T> putIfNotEmpty(rows: List<T>, insert: suspend (List<T>) -> Unit) { if (rows.isNotEmpty()) insert(rows) }
}
