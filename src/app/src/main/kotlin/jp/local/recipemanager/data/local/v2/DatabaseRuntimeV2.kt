package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.*
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer

@Dao
interface SystemDaoV2 {
    @Query("SELECT value FROM metadata WHERE `key` = :key") suspend fun metadata(key: String): String?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMetadata(row: MetadataEntityV2)
    @Insert suspend fun putAliases(rows: List<FoodMasterAliasEntityV2>)
    @Query("DELETE FROM holidays") suspend fun clearHolidays()
    @Insert suspend fun putHolidays(rows: List<HolidayEntityV2>)
}

/** Opens the existing database with the tested non-destructive migration. */
object DatabaseRuntimeV2 {
    const val DATABASE_NAME = "recipe-manager.db"
    fun build(context: Context, name: String = DATABASE_NAME): RecipeManagerDatabaseV2 =
        Room.databaseBuilder(context.applicationContext, RecipeManagerDatabaseV2::class.java, name)
            .addMigrations(Migration1To2).build()

    suspend fun initialize(context: Context, database: RecipeManagerDatabaseV2) {
        val mapper = JsonInput.mapper()
        val food = context.assets.open("masters/food_master_v1.json").use(mapper::readTree)
        val holidays = context.assets.open("masters/holidays_2026_2027.json").use(mapper::readTree)
        database.withTransaction {
            val system = database.systemDao()
            val dao = database.foodDao()
            if (system.metadata("food_master_version") != food.path("version").asText()) {
                val proposed = food.path("items").toList()
                val replacing = proposed.map { it.path("id").asText() }.toSet()
                val owners = dao.names().filter { it.foodMasterId !in replacing }.associate { it.normalizedName to it.foodMasterId }.toMutableMap()
                // User aliases on presets also remain protected across preset updates.
                val userAliases = database.backupDao().userAliases()
                val protectedAliases = userAliases.map { it.normalizedAlias }.toSet()
                userAliases.forEach { owners[it.normalizedAlias] = it.foodMasterId }
                proposed.forEach { item ->
                    val id = item.path("id").asText()
                    val names = (listOf(item.path("name").asText()) + item.path("aliases").map { it.asText() }).map(TextNormalizer::normalize).distinct()
                    names.forEach { name ->
                        require(name !in protectedAliases) { "内蔵材料の更新で利用者別名と競合しています: ${item.path("name").asText()}" }
                        require(owners[name] == null || owners[name] == id) { "内蔵材料の更新で名称・別名が競合しています: ${item.path("name").asText()}" }
                        owners[name] = id
                    }
                }
                proposed.forEach { item ->
                    val id = item.path("id").asText()
                    val old = dao.find(id)
                    require(old == null || !old.userEditable) { "内蔵材料IDが利用者材料と競合しています" }
                    val row = FoodMasterEntityV2(id, item.path("name").asText(), TextNormalizer.normalize(item.path("name").asText()),
                        old?.baseUnit.orEmpty(), item.path("isMainIngredient").asBoolean(), false, old?.revision?.plus(1) ?: 1)
                    if (old == null) dao.insertMaster(row) else dao.updateMaster(row)
                    dao.clearBuiltInAliases(id); dao.clearGroups(id); dao.clearSeasons(id)
                    val aliases = item.path("aliases").map { FoodMasterAliasEntityV2(id, it.asText(), TextNormalizer.normalize(it.asText())) }.distinctBy { it.normalizedAlias }
                    if (aliases.isNotEmpty()) system.putAliases(aliases)
                    val groups = item.path("foodGroups").map { FoodMasterGroupEntityV2(id, it.asText()) }
                    if (groups.isNotEmpty()) dao.insertGroups(groups)
                    val seasons = item.path("seasonMonths").map { FoodMasterSeasonEntityV2(id, it.asInt()) }
                    if (seasons.isNotEmpty()) dao.insertSeasons(seasons)
                }
                system.putMetadata(MetadataEntityV2("food_master_version", food.path("version").asText()))
                database.recipeDao().all().forEach { recipe ->
                    dao.updateNutrition(recipe.recipe.id, dao.recipeNutrition(recipe.recipe.id).joinToString(","))
                }
                proposed.forEach { dao.markListsForFood(it.path("id").asText()) }
            }
            if (system.metadata("holiday_data_version") != "2026-2027-v1") {
                system.clearHolidays()
                system.putHolidays(holidays.map {
                    val date = it.path("date").asText()
                    HolidayEntityV2(date, it.path("name").asText(), date.take(4).toInt())
                })
                system.putMetadata(MetadataEntityV2("holiday_data_version", "2026-2027-v1"))
            }
        }
    }
}
