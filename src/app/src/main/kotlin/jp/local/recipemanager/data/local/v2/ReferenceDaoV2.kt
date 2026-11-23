package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ReferenceDaoV2 {
    @Query("SELECT * FROM drafts") suspend fun drafts(): List<DraftEntityV2>
    @Query("SELECT * FROM draftReferences") suspend fun references(): List<DraftReferenceEntityV2>
    @Query("SELECT * FROM recipes") suspend fun recipes(): List<RecipeEntityV2>
    @Query("SELECT * FROM foodMasters") suspend fun foods(): List<FoodMasterEntityV2>
    @Query("SELECT * FROM weeklyMenus") suspend fun menus(): List<WeeklyMenuEntityV2>
    @Query("SELECT * FROM menuDays") suspend fun days(): List<MenuDayEntityV2>
    @Update suspend fun updateDraft(draft: DraftEntityV2)
    @Query("DELETE FROM draftReferences WHERE draftId = :draftId") suspend fun deleteReferences(draftId: String)
    @Insert suspend fun insertReferences(references: List<DraftReferenceEntityV2>)
}
