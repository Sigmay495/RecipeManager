package jp.local.recipemanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import jp.local.recipemanager.core.text.TextNormalizer

data class FoodMasterWithDetails(
    @androidx.room.Embedded val master: FoodMasterEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "foodMasterId") val aliases: List<FoodMasterAliasEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "foodMasterId") val groups: List<FoodMasterGroupEntity>,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "foodMasterId") val seasons: List<FoodMasterSeasonEntity>,
)

@Dao
abstract class FoodMasterDao {
    @Transaction
    @Query("SELECT * FROM foodMasters ORDER BY name")
    abstract suspend fun getAll(): List<FoodMasterWithDetails>

    @Query("SELECT COUNT(*) FROM foodMasters WHERE userEditable = 0")
    abstract suspend fun builtInCount(): Int

    @Query("SELECT id FROM foodMasters WHERE normalizedName = :name UNION SELECT foodMasterId FROM foodMasterAliases WHERE normalizedAlias = :name LIMIT 1")
    abstract suspend fun findIdByNormalizedName(name: String): String?

    suspend fun findIdByName(name: String): String? = findIdByNormalizedName(TextNormalizer.normalize(name))

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMaster(master: FoodMasterEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAliases(aliases: List<FoodMasterAliasEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertGroups(groups: List<FoodMasterGroupEntity>)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSeasons(seasons: List<FoodMasterSeasonEntity>)

    @Transaction
    open suspend fun insert(details: FoodMasterWithDetails) {
        val allNames = buildSet {
            add(details.master.normalizedName)
            details.aliases.forEach { add(it.normalizedAlias) }
        }
        require(allNames.size == details.aliases.size + 1) { "標準名と別名が重複しています" }
        allNames.forEach { require(findIdByNormalizedName(it) == null) { "食材名が既存マスターと競合しています: $it" } }
        insertMaster(details.master)
        insertAliases(details.aliases)
        insertGroups(details.groups)
        insertSeasons(details.seasons)
    }
}
