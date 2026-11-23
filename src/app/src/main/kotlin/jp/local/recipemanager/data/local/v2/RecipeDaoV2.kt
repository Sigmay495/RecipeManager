package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update

data class RecipeDetailsV2(
    @Embedded val recipe: RecipeEntityV2,
    @Relation(parentColumn = "id", entityColumn = "recipeId") val ingredients: List<RecipeIngredientEntityV2>,
)

/** Raw persistence operations; the repository performs validation inside the write transaction. */
@Dao
abstract class RecipeDaoV2 {
    @Transaction
    @Query("SELECT * FROM recipes ORDER BY name, id")
    abstract suspend fun all(): List<RecipeDetailsV2>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun find(id: String): RecipeDetailsV2?

    @Query("SELECT * FROM recipeIngredients WHERE id = :id")
    abstract suspend fun ingredient(id: String): RecipeIngredientEntityV2?

    @Query("SELECT * FROM foodMasters WHERE id = :id")
    abstract suspend fun food(id: String): FoodMasterEntityV2?

    @Query("SELECT foodGroup FROM foodMasterGroups WHERE foodMasterId = :id")
    abstract suspend fun foodGroups(id: String): List<String>

    @Query("""SELECT MAX(h.cookedDate) FROM cookingHistories h
        JOIN historyItems i ON i.historyId = h.id WHERE i.recipeId = :recipeId""")
    abstract suspend fun lastCookedAt(recipeId: String): String?

    @Query("""SELECT COUNT(*) FROM menuItems i JOIN menuDays d ON d.id = i.menuDayId
        WHERE i.recipeId = :recipeId AND (i.locked = 1 OR d.dayLocked = 1) AND i.role != :category""")
    abstract suspend fun incompatibleFixedReferences(recipeId: String, category: String): Int

    @Query("""SELECT (SELECT COUNT(*) FROM historyItems WHERE recipeId = :recipeId)
        + (SELECT COUNT(*) FROM menuItems WHERE recipeId = :recipeId)
        + (SELECT COUNT(*) FROM postponedPlans WHERE recipeId = :recipeId)
        + (SELECT COUNT(*) FROM draftReferences WHERE entityType = 'recipe' AND entityId = :recipeId)""")
    abstract suspend fun protectedReferenceCount(recipeId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRecipe(recipe: RecipeEntityV2)

    @Update
    abstract suspend fun updateRecipe(recipe: RecipeEntityV2): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertIngredients(ingredients: List<RecipeIngredientEntityV2>)

    @Query("DELETE FROM recipeIngredients WHERE recipeId = :recipeId")
    abstract suspend fun deleteIngredients(recipeId: String)

    @Query("DELETE FROM recipes WHERE id = :recipeId AND revision = :revision")
    abstract suspend fun deleteRecipe(recipeId: String, revision: Long): Int

    @Query("""UPDATE shoppingLists SET needsRegeneration = 1 WHERE weekStart IN (
        SELECT w.weekStart FROM weeklyMenus w JOIN menuDays d ON d.weeklyMenuId = w.id
        JOIN menuItems i ON i.menuDayId = d.id WHERE i.recipeId = :recipeId)""")
    abstract suspend fun invalidateMenuShoppingLists(recipeId: String)

    @Query("SELECT * FROM shoppingItems")
    abstract suspend fun shoppingItems(): List<ShoppingItemEntityV2>

    @Query("UPDATE shoppingItems SET recipeUsagesJson = :json WHERE id = :id")
    abstract suspend fun updateShoppingUsages(id: String, json: String)

    @Query("UPDATE shoppingLists SET needsRegeneration = 1 WHERE id = :id")
    abstract suspend fun invalidateShoppingList(id: String)
}
