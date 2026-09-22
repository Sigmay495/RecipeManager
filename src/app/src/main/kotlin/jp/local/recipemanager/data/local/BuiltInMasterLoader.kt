package jp.local.recipemanager.data.local

import android.content.Context
import androidx.room.withTransaction
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.domain.model.FoodGroup
import org.json.JSONObject

class BuiltInMasterLoader(private val context: Context) {
    suspend fun ensureLoaded(database: RecipeManagerDatabase) {
        val root = context.assets.open(ASSET_PATH).bufferedReader().use { JSONObject(it.readText()) }
        val version = root.getString("version")
        if (database.systemDataDao().getMetadata(METADATA_KEY) == version) return

        database.withTransaction {
            val dao = database.foodMasterDao()
            val items = root.getJSONArray("items")
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                if (dao.findIdByNormalizedName(TextNormalizer.normalize(item.getString("name"))) != null) continue
                val id = item.getString("id")
                dao.insert(
                    FoodMasterWithDetails(
                        master = FoodMasterEntity(
                            id = id,
                            name = item.getString("name"),
                            normalizedName = TextNormalizer.normalize(item.getString("name")),
                            salesUnit = item.getString("salesUnit"),
                            isMainIngredient = item.getBoolean("isMainIngredient"),
                            userEditable = false,
                        ),
                        aliases = item.getJSONArray("aliases").toStringList().map {
                            FoodMasterAliasEntity(id, it, TextNormalizer.normalize(it))
                        },
                        groups = item.getJSONArray("foodGroups").toStringList().map {
                            FoodMasterGroupEntity(id, FoodGroup.valueOf(it))
                        },
                        seasons = item.getJSONArray("seasonMonths").toIntList().map {
                            require(it in 1..12)
                            FoodMasterSeasonEntity(id, it)
                        },
                    ),
                )
            }
            database.systemDataDao().putMetadata(MetadataEntity(METADATA_KEY, version))
        }
    }

    private fun org.json.JSONArray.toStringList() = (0 until length()).map(::getString)
    private fun org.json.JSONArray.toIntList() = (0 until length()).map(::getInt)

    companion object {
        const val ASSET_PATH = "masters/food_master_v1.json"
        const val METADATA_KEY = "food_master_version"
    }
}
