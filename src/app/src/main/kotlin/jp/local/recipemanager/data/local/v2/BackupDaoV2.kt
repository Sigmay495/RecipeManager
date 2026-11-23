package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** Includes local reference data for validation, but only user data is exported/replaced. */
data class BackupRowsV2(
    val recipes: List<RecipeEntityV2>,
    val ingredients: List<RecipeIngredientEntityV2>,
    val menus: List<WeeklyMenuEntityV2>,
    val days: List<MenuDayEntityV2>,
    val menuItems: List<MenuItemEntityV2>,
    val histories: List<CookingHistoryEntityV2>,
    val historyItems: List<HistoryItemEntityV2>,
    val outcomes: List<PlanOutcomeEntityV2>,
    val postponements: List<PostponedPlanEntityV2>,
    val shoppingLists: List<ShoppingListEntityV2>,
    val shoppingItems: List<ShoppingItemEntityV2>,
    val foods: List<FoodMasterEntityV2>,
    val builtInAliases: List<FoodMasterAliasEntityV2>,
    val userAliases: List<UserFoodAliasEntityV2>,
    val groups: List<FoodMasterGroupEntityV2>,
    val seasons: List<FoodMasterSeasonEntityV2>,
    val drafts: List<DraftEntityV2>,
    val references: List<DraftReferenceEntityV2>,
    val settings: List<SettingEntityV2>,
    val metadata: List<MetadataEntityV2>,
    val holidays: List<HolidayEntityV2>,
)

@Dao
abstract class BackupDaoV2 {
    @Query("SELECT * FROM recipes ORDER BY id") abstract suspend fun recipes(): List<RecipeEntityV2>
    @Query("SELECT * FROM recipeIngredients ORDER BY id") abstract suspend fun ingredients(): List<RecipeIngredientEntityV2>
    @Query("SELECT * FROM weeklyMenus ORDER BY id") abstract suspend fun menus(): List<WeeklyMenuEntityV2>
    @Query("SELECT * FROM menuDays ORDER BY id") abstract suspend fun days(): List<MenuDayEntityV2>
    @Query("SELECT * FROM menuItems ORDER BY id") abstract suspend fun menuItems(): List<MenuItemEntityV2>
    @Query("SELECT * FROM cookingHistories ORDER BY id") abstract suspend fun histories(): List<CookingHistoryEntityV2>
    @Query("SELECT * FROM historyItems ORDER BY id") abstract suspend fun historyItems(): List<HistoryItemEntityV2>
    @Query("SELECT * FROM planOutcomes ORDER BY id") abstract suspend fun outcomes(): List<PlanOutcomeEntityV2>
    @Query("SELECT * FROM postponedPlans ORDER BY id") abstract suspend fun postponements(): List<PostponedPlanEntityV2>
    @Query("SELECT * FROM shoppingLists ORDER BY id") abstract suspend fun shoppingLists(): List<ShoppingListEntityV2>
    @Query("SELECT * FROM shoppingItems ORDER BY id") abstract suspend fun shoppingItems(): List<ShoppingItemEntityV2>
    @Query("SELECT * FROM foodMasters ORDER BY id") abstract suspend fun foods(): List<FoodMasterEntityV2>
    @Query("SELECT * FROM foodMasterAliases ORDER BY foodMasterId, normalizedAlias") abstract suspend fun builtInAliases(): List<FoodMasterAliasEntityV2>
    @Query("SELECT * FROM userFoodAliases ORDER BY id") abstract suspend fun userAliases(): List<UserFoodAliasEntityV2>
    @Query("SELECT * FROM foodMasterGroups ORDER BY foodMasterId, foodGroup") abstract suspend fun groups(): List<FoodMasterGroupEntityV2>
    @Query("SELECT * FROM foodMasterSeasons ORDER BY foodMasterId, month") abstract suspend fun seasons(): List<FoodMasterSeasonEntityV2>
    @Query("SELECT * FROM drafts ORDER BY id") abstract suspend fun drafts(): List<DraftEntityV2>
    @Query("SELECT * FROM draftReferences ORDER BY draftId, entityType, entityId") abstract suspend fun references(): List<DraftReferenceEntityV2>
    @Query("SELECT * FROM settings ORDER BY key") abstract suspend fun settings(): List<SettingEntityV2>
    @Query("SELECT * FROM metadata ORDER BY key") abstract suspend fun metadata(): List<MetadataEntityV2>
    @Query("SELECT * FROM holidays ORDER BY date") abstract suspend fun holidays(): List<HolidayEntityV2>

    @Transaction
    open suspend fun snapshot() = BackupRowsV2(
        recipes(), ingredients(), menus(), days(), menuItems(), histories(), historyItems(), outcomes(), postponements(), shoppingLists(), shoppingItems(), foods(), builtInAliases(), userAliases(), groups(), seasons(), drafts(), references(), settings(), metadata(), holidays()
    )

    @Insert abstract suspend fun putFoods(rows: List<FoodMasterEntityV2>)
    @Insert abstract suspend fun putGroups(rows: List<FoodMasterGroupEntityV2>)
    @Insert abstract suspend fun putSeasons(rows: List<FoodMasterSeasonEntityV2>)
    @Insert abstract suspend fun putUserAliases(rows: List<UserFoodAliasEntityV2>)
    @Insert abstract suspend fun putRecipes(rows: List<RecipeEntityV2>)
    @Insert abstract suspend fun putIngredients(rows: List<RecipeIngredientEntityV2>)
    @Insert abstract suspend fun putMenus(rows: List<WeeklyMenuEntityV2>)
    @Insert abstract suspend fun putDays(rows: List<MenuDayEntityV2>)
    @Insert abstract suspend fun putMenuItems(rows: List<MenuItemEntityV2>)
    @Insert abstract suspend fun putHistories(rows: List<CookingHistoryEntityV2>)
    @Insert abstract suspend fun putHistoryItems(rows: List<HistoryItemEntityV2>)
    @Insert abstract suspend fun putOutcomes(rows: List<PlanOutcomeEntityV2>)
    @Insert abstract suspend fun putPostponements(rows: List<PostponedPlanEntityV2>)
    @Insert abstract suspend fun putShoppingLists(rows: List<ShoppingListEntityV2>)
    @Insert abstract suspend fun putShoppingItems(rows: List<ShoppingItemEntityV2>)
    @Insert abstract suspend fun putDrafts(rows: List<DraftEntityV2>)
    @Insert abstract suspend fun putReferences(rows: List<DraftReferenceEntityV2>)
    @Insert abstract suspend fun putSettings(rows: List<SettingEntityV2>)

    @Query("DELETE FROM draftReferences") protected abstract suspend fun clearReferences()
    @Query("DELETE FROM drafts") protected abstract suspend fun clearDrafts()
    @Query("DELETE FROM postponedPlans") protected abstract suspend fun clearPostponements()
    @Query("DELETE FROM planOutcomes") protected abstract suspend fun clearOutcomes()
    @Query("DELETE FROM shoppingItems") protected abstract suspend fun clearShoppingItems()
    @Query("DELETE FROM shoppingLists") protected abstract suspend fun clearShoppingLists()
    @Query("DELETE FROM historyItems") protected abstract suspend fun clearHistoryItems()
    @Query("DELETE FROM cookingHistories") protected abstract suspend fun clearHistories()
    @Query("DELETE FROM menuItems") protected abstract suspend fun clearMenuItems()
    @Query("DELETE FROM menuDays") protected abstract suspend fun clearDays()
    @Query("DELETE FROM weeklyMenus") protected abstract suspend fun clearMenus()
    @Query("DELETE FROM recipeIngredients") protected abstract suspend fun clearIngredients()
    @Query("DELETE FROM recipes") protected abstract suspend fun clearRecipes()
    @Query("DELETE FROM userFoodAliases") protected abstract suspend fun clearUserAliases()
    @Query("DELETE FROM foodMasterGroups WHERE foodMasterId IN (SELECT id FROM foodMasters WHERE userEditable = 1)") protected abstract suspend fun clearGroups()
    @Query("DELETE FROM foodMasterSeasons WHERE foodMasterId IN (SELECT id FROM foodMasters WHERE userEditable = 1)") protected abstract suspend fun clearSeasons()
    @Query("DELETE FROM foodMasters WHERE userEditable = 1") protected abstract suspend fun clearFoods()
    @Query("DELETE FROM settings") protected abstract suspend fun clearSettings()

    @Query("UPDATE recipes SET lastCookedAt = (SELECT MAX(h.cookedDate) FROM cookingHistories h JOIN historyItems i ON i.historyId = h.id WHERE i.recipeId = recipes.id)")
    abstract suspend fun recalculateHistory()

    @Query("INSERT OR REPLACE INTO settings (`key`, value) VALUES (:key, :value)")
    abstract suspend fun putSetting(key: String, value: String)

    /** Caller revalidates inside the same transaction before replacing any rows. */
    @Transaction
    open suspend fun replace(rows: BackupRowsV2) {
        clearReferences()
        clearDrafts()
        clearPostponements()
        clearOutcomes()
        clearShoppingItems()
        clearShoppingLists()
        clearHistoryItems()
        clearHistories()
        clearMenuItems()
        clearDays()
        clearMenus()
        clearIngredients()
        clearRecipes()
        clearUserAliases()
        clearGroups()
        clearSeasons()
        clearFoods()
        clearSettings()
        if (rows.foods.isNotEmpty()) putFoods(rows.foods)
        if (rows.groups.isNotEmpty()) putGroups(rows.groups)
        if (rows.seasons.isNotEmpty()) putSeasons(rows.seasons)
        if (rows.userAliases.isNotEmpty()) putUserAliases(rows.userAliases)
        if (rows.recipes.isNotEmpty()) putRecipes(rows.recipes)
        if (rows.ingredients.isNotEmpty()) putIngredients(rows.ingredients)
        if (rows.menus.isNotEmpty()) putMenus(rows.menus)
        if (rows.days.isNotEmpty()) putDays(rows.days)
        if (rows.menuItems.isNotEmpty()) putMenuItems(rows.menuItems)
        if (rows.histories.isNotEmpty()) putHistories(rows.histories)
        if (rows.historyItems.isNotEmpty()) putHistoryItems(rows.historyItems)
        if (rows.outcomes.isNotEmpty()) putOutcomes(rows.outcomes)
        if (rows.postponements.isNotEmpty()) putPostponements(rows.postponements)
        if (rows.shoppingLists.isNotEmpty()) putShoppingLists(rows.shoppingLists)
        if (rows.shoppingItems.isNotEmpty()) putShoppingItems(rows.shoppingItems)
        if (rows.drafts.isNotEmpty()) putDrafts(rows.drafts)
        if (rows.references.isNotEmpty()) putReferences(rows.references)
        if (rows.settings.isNotEmpty()) putSettings(rows.settings)
        recalculateHistory()
    }
}
