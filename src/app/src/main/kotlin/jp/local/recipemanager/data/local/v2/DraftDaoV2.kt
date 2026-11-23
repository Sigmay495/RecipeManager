package jp.local.recipemanager.data.local.v2

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface DraftDaoV2 {
    @Query("SELECT * FROM drafts ORDER BY kind, targetKey") suspend fun all(): List<DraftEntityV2>
    @Query("SELECT * FROM drafts WHERE id = :id") suspend fun find(id: String): DraftEntityV2?
    @Query("SELECT * FROM drafts WHERE kind = :kind AND targetKey = :key") suspend fun target(kind: String, key: String): DraftEntityV2?
    @Query("SELECT * FROM draftReferences WHERE draftId = :id") suspend fun references(id: String): List<DraftReferenceEntityV2>
    @Insert suspend fun insert(row: DraftEntityV2)
    @Update suspend fun update(row: DraftEntityV2)
    @Query("DELETE FROM draftReferences WHERE draftId = :id") suspend fun clearReferences(id: String)
    @Insert suspend fun insertReferences(rows: List<DraftReferenceEntityV2>)
    @Query("DELETE FROM drafts WHERE id = :id") suspend fun delete(id: String)
}
