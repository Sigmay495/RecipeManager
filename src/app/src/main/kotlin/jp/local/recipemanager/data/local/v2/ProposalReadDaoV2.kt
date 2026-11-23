package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Query

/** One transaction snapshot for proposal computation and optimistic adoption checks. */
@Dao
interface ProposalReadDaoV2 {
    @Query("SELECT * FROM menuItems ORDER BY id") suspend fun items(): List<MenuItemEntityV2>
    @Query("SELECT * FROM planOutcomes ORDER BY id") suspend fun outcomes(): List<PlanOutcomeEntityV2>
    @Query("SELECT * FROM postponedPlans ORDER BY id") suspend fun postponements(): List<PostponedPlanEntityV2>
    @Query("SELECT * FROM cookingHistories ORDER BY id") suspend fun histories(): List<CookingHistoryEntityV2>
    @Query("SELECT * FROM historyItems ORDER BY id") suspend fun historyItems(): List<HistoryItemEntityV2>
    @Query("SELECT * FROM holidays WHERE date BETWEEN :from AND :to ORDER BY date") suspend fun holidays(from: String, to: String): List<HolidayEntityV2>
    @Query("SELECT * FROM foodMasterGroups ORDER BY foodMasterId, foodGroup") suspend fun groups(): List<FoodMasterGroupEntityV2>
    @Query("SELECT * FROM foodMasterSeasons ORDER BY foodMasterId, month") suspend fun seasons(): List<FoodMasterSeasonEntityV2>
    @Query("SELECT * FROM foodMasterAliases ORDER BY foodMasterId, normalizedAlias") suspend fun builtInAliases(): List<FoodMasterAliasEntityV2>
    @Query("SELECT * FROM userFoodAliases ORDER BY id") suspend fun userAliases(): List<UserFoodAliasEntityV2>
}
