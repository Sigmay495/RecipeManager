package jp.local.recipemanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class RecipeWithIngredients(
    @androidx.room.Embedded val recipe: RecipeEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "recipeId") val ingredients: List<RecipeIngredientEntity>,
)

@Dao
abstract class RecipeDao {
    @Transaction
    @Query("SELECT * FROM recipes WHERE status = 'ACTIVE' ORDER BY name")
    abstract fun observeActive(): Flow<List<RecipeWithIngredients>>

    @Transaction
    @Query("SELECT * FROM recipes ORDER BY name")
    abstract fun observeAll(): Flow<List<RecipeWithIngredients>>

    @Transaction
    @Query("SELECT * FROM recipes ORDER BY name")
    abstract suspend fun getAll(): List<RecipeWithIngredients>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract fun observeById(id: String): Flow<RecipeWithIngredients?>

    @Transaction
    @Query("SELECT * FROM recipes WHERE id = :id")
    abstract suspend fun findById(id: String): RecipeWithIngredients?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRecipe(recipe: RecipeEntity)

    @Update
    protected abstract suspend fun updateRecipe(recipe: RecipeEntity)

    @Query("DELETE FROM recipeIngredients WHERE recipeId = :recipeId")
    protected abstract suspend fun deleteIngredients(recipeId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertIngredients(ingredients: List<RecipeIngredientEntity>)

    @Transaction
    open suspend fun insert(recipe: RecipeEntity, ingredients: List<RecipeIngredientEntity>) {
        require(ingredients.all { it.recipeId == recipe.id })
        insertRecipe(recipe)
        insertIngredients(ingredients)
    }

    @Transaction
    open suspend fun update(recipe: RecipeEntity, ingredients: List<RecipeIngredientEntity>) {
        require(ingredients.all { it.recipeId == recipe.id })
        updateRecipe(recipe)
        deleteIngredients(recipe.id)
        insertIngredients(ingredients)
    }

    @Query("UPDATE recipes SET status = 'DELETED', deletedAt = :at, updatedAt = :at WHERE id = :id")
    abstract suspend fun markDeleted(id: String, at: Instant): Int

    @Query("UPDATE recipes SET status = 'ACTIVE', deletedAt = NULL, updatedAt = :at WHERE id = :id")
    abstract suspend fun restore(id: String, at: Instant): Int

    @Query("SELECT COUNT(*) FROM recipes WHERE normalizedName = :normalizedName AND id != :excludedId")
    abstract suspend fun countByNormalizedName(normalizedName: String, excludedId: String = ""): Int

    @Query("SELECT COUNT(*) FROM recipes WHERE sourceUrl = :sourceUrl AND id != :excludedId")
    abstract suspend fun countBySourceUrl(sourceUrl: String, excludedId: String = ""): Int
}
