package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update

data class ShoppingListDetailsV2(
    @Embedded val list: ShoppingListEntityV2,
    @Relation(parentColumn = "id", entityColumn = "shoppingListId") val items: List<ShoppingItemEntityV2>,
)

@Dao
interface ShoppingDaoV2 {
    @Transaction @Query("SELECT * FROM shoppingLists WHERE weekStart = :start") suspend fun week(start: String): ShoppingListDetailsV2?
    @Query("SELECT * FROM shoppingItems WHERE id = :id") suspend fun item(id: String): ShoppingItemEntityV2?
    @Insert suspend fun insertList(row: ShoppingListEntityV2)
    @Update suspend fun updateList(row: ShoppingListEntityV2)
    @Insert suspend fun insertItems(rows: List<ShoppingItemEntityV2>)
    @Update suspend fun updateItem(row: ShoppingItemEntityV2)
    @Query("DELETE FROM shoppingItems WHERE shoppingListId = :id") suspend fun clearItems(id: String)
}
