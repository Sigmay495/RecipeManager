package jp.local.recipemanager.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import jp.local.recipemanager.domain.model.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Entity(tableName = "recipes", indices = [Index("normalizedName"), Index("category"), Index("effortLevel"), Index("status"), Index("lastCookedAt"), Index("updatedAt")])
data class RecipeEntity(
    @androidx.room.PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val category: RecipeCategory,
    val cookingTimeMinutes: Int?,
    val effortLevel: EffortLevel,
    val sourceUrl: String?,
    val memo: String,
    val lastCookedAt: LocalDate?,
    val initialLastCookedAt: LocalDate?,
    val nutritionGroups: Set<FoodGroup>,
    val status: RecipeStatus,
    val deletedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

@Entity(
    tableName = "recipeIngredients",
    foreignKeys = [
        ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FoodMasterEntity::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("recipeId"), Index("normalizedName"), Index("foodMasterId"), Index(value = ["recipeId", "displayOrder"], unique = true)],
)
data class RecipeIngredientEntity(
    @androidx.room.PrimaryKey val id: String,
    val recipeId: String,
    val displayOrder: Int,
    val name: String,
    val normalizedName: String,
    val amount: BigDecimal?,
    val unit: String,
    val note: String,
    val foodMasterId: String?,
)

@Entity(tableName = "weeklyMenus", indices = [Index(value = ["weekStart"], unique = true), Index("updatedAt")])
data class WeeklyMenuEntity(@androidx.room.PrimaryKey val id: String, val weekStart: LocalDate, val createdAt: Instant, val updatedAt: Instant)

@Entity(tableName = "menuDays", foreignKeys = [ForeignKey(entity = WeeklyMenuEntity::class, parentColumns = ["id"], childColumns = ["weeklyMenuId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("weeklyMenuId"), Index(value = ["date"], unique = true)])
data class MenuDayEntity(@androidx.room.PrimaryKey val id: String, val weeklyMenuId: String, val date: LocalDate, val noMeal: Boolean, val noMealReason: String, val noMealMemo: String)

@Entity(tableName = "menuItems", foreignKeys = [ForeignKey(entity = MenuDayEntity::class, parentColumns = ["id"], childColumns = ["menuDayId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("menuDayId"), Index("recipeId"), Index("role"), Index(value = ["menuDayId", "role"], unique = true)])
data class MenuItemEntity(@androidx.room.PrimaryKey val id: String, val menuDayId: String, val recipeId: String, val recipeNameSnapshot: String, val role: MenuRole)

@Entity(tableName = "cookingHistories", indices = [Index(value = ["cookedDate"], unique = true), Index("updatedAt")])
data class CookingHistoryEntity(@androidx.room.PrimaryKey val id: String, val cookedDate: LocalDate, val createdAt: Instant, val updatedAt: Instant)

@Entity(tableName = "historyItems", foreignKeys = [ForeignKey(entity = CookingHistoryEntity::class, parentColumns = ["id"], childColumns = ["historyId"], onDelete = ForeignKey.RESTRICT), ForeignKey(entity = RecipeEntity::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("historyId"), Index("recipeId"), Index("role")])
data class HistoryItemEntity(@androidx.room.PrimaryKey val id: String, val historyId: String, val recipeId: String, val recipeNameSnapshot: String, val role: MenuRole)

@Entity(tableName = "shoppingLists", indices = [Index(value = ["weekStart"], unique = true), Index("sourceMenuUpdatedAt")])
data class ShoppingListEntity(@androidx.room.PrimaryKey val id: String, val weekStart: LocalDate, val sourceMenuUpdatedAt: Instant, val createdAt: Instant)

@Entity(tableName = "shoppingItems", foreignKeys = [ForeignKey(entity = ShoppingListEntity::class, parentColumns = ["id"], childColumns = ["shoppingListId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("shoppingListId"), Index("normalizedName"), Index("checked"), Index("excluded")])
data class ShoppingItemEntity(@androidx.room.PrimaryKey val id: String, val shoppingListId: String, val name: String, val normalizedName: String, val amount: BigDecimal?, val unit: String, val recipeNames: String, val checked: Boolean, val excluded: Boolean)

@Entity(tableName = "foodMasters", indices = [Index(value = ["normalizedName"], unique = true), Index("userEditable")])
data class FoodMasterEntity(@androidx.room.PrimaryKey val id: String, val name: String, val normalizedName: String, val salesUnit: String, val isMainIngredient: Boolean, val userEditable: Boolean)

@Entity(tableName = "foodMasterAliases", primaryKeys = ["foodMasterId", "normalizedAlias"], foreignKeys = [ForeignKey(entity = FoodMasterEntity::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)], indices = [Index(value = ["normalizedAlias"], unique = true), Index("foodMasterId")])
data class FoodMasterAliasEntity(val foodMasterId: String, val alias: String, val normalizedAlias: String)

@Entity(tableName = "foodMasterGroups", primaryKeys = ["foodMasterId", "foodGroup"], foreignKeys = [ForeignKey(entity = FoodMasterEntity::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("foodGroup"), Index("foodMasterId")])
data class FoodMasterGroupEntity(val foodMasterId: String, val foodGroup: FoodGroup)

@Entity(tableName = "foodMasterSeasons", primaryKeys = ["foodMasterId", "month"], foreignKeys = [ForeignKey(entity = FoodMasterEntity::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("month"), Index("foodMasterId")])
data class FoodMasterSeasonEntity(val foodMasterId: String, val month: Int)

@Entity(tableName = "holidays", indices = [Index("year")])
data class HolidayEntity(@androidx.room.PrimaryKey val date: LocalDate, val name: String, val year: Int)

@Entity(tableName = "settings")
data class SettingEntity(@androidx.room.PrimaryKey val key: String, val value: String)

@Entity(tableName = "metadata")
data class MetadataEntity(@androidx.room.PrimaryKey val key: String, val value: String)
