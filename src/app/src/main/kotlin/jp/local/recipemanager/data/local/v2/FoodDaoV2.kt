package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

data class FoodNameOwnerV2(val normalizedName: String, val foodMasterId: String)

@Dao
interface FoodDaoV2 {
    @Query("SELECT * FROM foodMasters WHERE id = :id")
    suspend fun find(id: String): FoodMasterEntityV2?

    @Query("SELECT * FROM foodMasters ORDER BY normalizedName, id")
    suspend fun all(): List<FoodMasterEntityV2>

    @Query("""SELECT * FROM foodMasters WHERE :query = '' OR instr(normalizedName, :query) > 0
        OR id IN (SELECT foodMasterId FROM foodMasterAliases WHERE normalizedAlias = :query)
        OR id IN (SELECT foodMasterId FROM userFoodAliases WHERE normalizedAlias = :query)
        ORDER BY normalizedName, id""")
    suspend fun search(query: String): List<FoodMasterEntityV2>

    @Query("""SELECT id FROM foodMasters WHERE normalizedName = :normalizedName
        UNION SELECT foodMasterId FROM foodMasterAliases WHERE normalizedAlias = :normalizedName
        UNION SELECT foodMasterId FROM userFoodAliases WHERE normalizedAlias = :normalizedName""")
    suspend fun exactIds(normalizedName: String): List<String>

    @Query("""SELECT normalizedName, id AS foodMasterId FROM foodMasters
        UNION ALL SELECT normalizedAlias AS normalizedName, foodMasterId FROM foodMasterAliases
        UNION ALL SELECT normalizedAlias AS normalizedName, foodMasterId FROM userFoodAliases""")
    suspend fun names(): List<FoodNameOwnerV2>

    @Query("SELECT * FROM userFoodAliases WHERE foodMasterId = :id ORDER BY normalizedAlias, id")
    suspend fun aliases(id: String): List<UserFoodAliasEntityV2>

    @Query("SELECT * FROM foodMasterAliases WHERE foodMasterId = :id ORDER BY normalizedAlias")
    suspend fun builtInAliases(id: String): List<FoodMasterAliasEntityV2>

    @Query("SELECT foodGroup FROM foodMasterGroups WHERE foodMasterId = :id ORDER BY foodGroup")
    suspend fun groups(id: String): List<String>

    @Query("SELECT month FROM foodMasterSeasons WHERE foodMasterId = :id ORDER BY month")
    suspend fun seasons(id: String): List<Int>

    @Insert suspend fun insertMaster(master: FoodMasterEntityV2)
    @Update suspend fun updateMaster(master: FoodMasterEntityV2)
    @Insert suspend fun insertAliases(aliases: List<UserFoodAliasEntityV2>)
    @Insert suspend fun insertGroups(groups: List<FoodMasterGroupEntityV2>)
    @Insert suspend fun insertSeasons(seasons: List<FoodMasterSeasonEntityV2>)
    @Query("DELETE FROM userFoodAliases WHERE foodMasterId = :id") suspend fun clearAliases(id: String)
    @Query("DELETE FROM foodMasterAliases WHERE foodMasterId = :id") suspend fun clearBuiltInAliases(id: String)
    @Query("DELETE FROM foodMasterGroups WHERE foodMasterId = :id") suspend fun clearGroups(id: String)
    @Query("DELETE FROM foodMasterSeasons WHERE foodMasterId = :id") suspend fun clearSeasons(id: String)
    @Query("DELETE FROM foodMasters WHERE id = :id") suspend fun deleteMaster(id: String)
    @Query("SELECT COUNT(*) FROM recipeIngredients WHERE foodMasterId = :id") suspend fun ingredientReferences(id: String): Int
    @Query("SELECT COUNT(*) FROM shoppingItems WHERE foodMasterId = :id") suspend fun shoppingReferences(id: String): Int

    @Query("""SELECT DISTINCT r.* FROM recipes r JOIN recipeIngredients i ON i.recipeId = r.id
        WHERE i.foodMasterId = :id ORDER BY r.id""")
    suspend fun recipesForFood(id: String): List<RecipeEntityV2>

    @Query("""SELECT DISTINCT g.foodGroup FROM recipeIngredients i
        JOIN foodMasterGroups g ON g.foodMasterId = i.foodMasterId WHERE i.recipeId = :id ORDER BY g.foodGroup""")
    suspend fun recipeNutrition(id: String): List<String>

    @Query("UPDATE recipes SET nutritionGroups = :groups WHERE id = :id")
    suspend fun updateNutrition(id: String, groups: String)

    @Query("UPDATE recipes SET nutritionGroups = :groups, revision = :revision, updatedAt = :now WHERE id = :id")
    suspend fun updateMergedRecipe(id: String, groups: String, revision: Long, now: String)

    @Query("UPDATE recipeIngredients SET foodMasterId = :targetId WHERE foodMasterId = :sourceId")
    suspend fun moveIngredients(sourceId: String, targetId: String)

    @Query("UPDATE userFoodAliases SET foodMasterId = :targetId, updatedAt = :now WHERE foodMasterId = :sourceId")
    suspend fun moveAliases(sourceId: String, targetId: String, now: String)

    @Query("SELECT * FROM shoppingItems WHERE foodMasterId = :sourceId OR foodMasterId = :targetId ORDER BY shoppingListId, unit, id")
    suspend fun shoppingForMerge(sourceId: String, targetId: String): List<ShoppingItemEntityV2>

    @Query("DELETE FROM shoppingItems WHERE id IN (:ids)") suspend fun deleteShoppingItems(ids: List<String>)
    @Insert suspend fun insertShoppingItems(items: List<ShoppingItemEntityV2>)
    @Query("UPDATE shoppingLists SET needsRegeneration = 1 WHERE id IN (:ids)")
    suspend fun markShoppingLists(ids: List<String>)

    @Query("""UPDATE shoppingLists SET needsRegeneration = 1 WHERE id IN
        (SELECT shoppingListId FROM shoppingItems WHERE foodMasterId = :id)
        OR weekStart IN (SELECT w.weekStart FROM weeklyMenus w JOIN menuDays d ON d.weeklyMenuId = w.id
            JOIN menuItems m ON m.menuDayId = d.id JOIN recipeIngredients i ON i.recipeId = m.recipeId
            WHERE i.foodMasterId = :id)""")
    suspend fun markListsForFood(id: String)

    @Query("""UPDATE shoppingLists SET needsRegeneration = 1 WHERE id IN
        (SELECT shoppingListId FROM shoppingItems WHERE foodMasterId IS NULL AND normalizedName IN (:names))
        OR weekStart IN (SELECT w.weekStart FROM weeklyMenus w JOIN menuDays d ON d.weeklyMenuId = w.id
            JOIN menuItems m ON m.menuDayId = d.id JOIN recipeIngredients i ON i.recipeId = m.recipeId
            WHERE i.foodMasterId IS NULL AND i.normalizedName IN (:names))""")
    suspend fun markUnresolvedLists(names: List<String>)
}
