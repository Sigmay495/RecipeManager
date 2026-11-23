package jp.local.recipemanager.data.local.v2

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Storage contract for v2. Dates, UTC instants, JSON and decimals retain their exact text. */
@Entity(tableName = "recipes", indices = [Index("normalizedName"), Index("category"), Index("effortLevel"), Index("proposalPriority"), Index("lastCookedAt"), Index("updatedAt")])
data class RecipeEntityV2(
    @PrimaryKey val id: String,
    val name: String,
    val normalizedName: String,
    val category: String,
    val cookingTimeMinutes: Int?,
    val effortLevel: String,
    val sourceUrl: String?,
    val memo: String,
    val lastCookedAt: String?,
    val nutritionGroups: String,
    val proposalPriority: String,
    val revision: Long,
    val createdAt: String,
    val updatedAt: String,
)

@Entity(
    tableName = "recipeIngredients",
    foreignKeys = [
        ForeignKey(entity = RecipeEntityV2::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FoodMasterEntityV2::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("recipeId"), Index("normalizedName"), Index("foodMasterId"), Index(value = ["recipeId", "displayOrder"], unique = true)],
)
data class RecipeIngredientEntityV2(
    @PrimaryKey val id: String,
    val recipeId: String,
    val displayOrder: Int,
    val name: String,
    val normalizedName: String,
    val amount: String?,
    val unit: String,
    val note: String,
    val foodMasterId: String?,
)

@Entity(tableName = "weeklyMenus", indices = [Index(value = ["weekStart"], unique = true), Index("updatedAt")])
data class WeeklyMenuEntityV2(@PrimaryKey val id: String, val weekStart: String, val createdAt: String, val updatedAt: String, val revision: Long)

@Entity(
    tableName = "menuDays",
    foreignKeys = [ForeignKey(entity = WeeklyMenuEntityV2::class, parentColumns = ["id"], childColumns = ["weeklyMenuId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("weeklyMenuId"), Index(value = ["date"], unique = true)],
)
data class MenuDayEntityV2(@PrimaryKey val id: String, val weeklyMenuId: String, val date: String, val noMeal: Boolean, val noMealReason: String, val noMealMemo: String, val requirementOrigin: String, val dayLocked: Boolean, val revision: Long)

@Entity(
    tableName = "menuItems",
    foreignKeys = [
        ForeignKey(entity = MenuDayEntityV2::class, parentColumns = ["id"], childColumns = ["menuDayId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = RecipeEntityV2::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("menuDayId"), Index("recipeId"), Index("role"), Index(value = ["menuDayId", "role"], unique = true), Index(value = ["menuDayId", "recipeId"], unique = true)],
)
data class MenuItemEntityV2(@PrimaryKey val id: String, val menuDayId: String, val recipeId: String, val recipeNameSnapshot: String, val role: String, val locked: Boolean)

@Entity(tableName = "cookingHistories", indices = [Index(value = ["cookedDate"], unique = true), Index("updatedAt")])
data class CookingHistoryEntityV2(@PrimaryKey val id: String, val cookedDate: String, val createdAt: String, val updatedAt: String)

@Entity(
    tableName = "historyItems",
    foreignKeys = [
        ForeignKey(entity = CookingHistoryEntityV2::class, parentColumns = ["id"], childColumns = ["historyId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = RecipeEntityV2::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("historyId"), Index("recipeId"), Index("role"), Index(value = ["historyId", "recipeId"], unique = true)],
)
data class HistoryItemEntityV2(@PrimaryKey val id: String, val historyId: String, val recipeId: String, val recipeNameSnapshot: String, val role: String)

@Entity(
    tableName = "planOutcomes",
    foreignKeys = [
        ForeignKey(entity = MenuItemEntityV2::class, parentColumns = ["id"], childColumns = ["menuItemId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HistoryItemEntityV2::class, parentColumns = ["id"], childColumns = ["historyItemId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["menuItemId"], unique = true), Index("historyItemId")],
)
data class PlanOutcomeEntityV2(@PrimaryKey val id: String, val menuItemId: String, val state: String, val historyItemId: String?, val updatedAt: String)

@Entity(
    tableName = "postponedPlans",
    foreignKeys = [
        ForeignKey(entity = MenuItemEntityV2::class, parentColumns = ["id"], childColumns = ["sourceMenuItemId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = RecipeEntityV2::class, parentColumns = ["id"], childColumns = ["recipeId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = MenuItemEntityV2::class, parentColumns = ["id"], childColumns = ["targetMenuItemId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["sourceMenuItemId"], unique = true), Index("recipeId"), Index("targetMenuItemId"), Index("targetDate")],
)
data class PostponedPlanEntityV2(@PrimaryKey val id: String, val sourceMenuItemId: String, val recipeId: String, val targetDate: String, val state: String, val targetMenuItemId: String?, val updatedAt: String)

@Entity(tableName = "foodMasters", indices = [Index(value = ["normalizedName"], unique = true), Index("userEditable")])
data class FoodMasterEntityV2(@PrimaryKey val id: String, val name: String, val normalizedName: String, val baseUnit: String, val isMainIngredient: Boolean, val userEditable: Boolean, val revision: Long)

@Entity(
    tableName = "foodMasterAliases", primaryKeys = ["foodMasterId", "normalizedAlias"],
    foreignKeys = [ForeignKey(entity = FoodMasterEntityV2::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["normalizedAlias"], unique = true), Index("foodMasterId")],
)
data class FoodMasterAliasEntityV2(val foodMasterId: String, val alias: String, val normalizedAlias: String)

@Entity(
    tableName = "userFoodAliases",
    foreignKeys = [ForeignKey(entity = FoodMasterEntityV2::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("foodMasterId"), Index(value = ["normalizedAlias"], unique = true)],
)
data class UserFoodAliasEntityV2(@PrimaryKey val id: String, val foodMasterId: String, val alias: String, val normalizedAlias: String, val updatedAt: String)

@Entity(
    tableName = "foodMasterGroups", primaryKeys = ["foodMasterId", "foodGroup"],
    foreignKeys = [ForeignKey(entity = FoodMasterEntityV2::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("foodGroup"), Index("foodMasterId")],
)
data class FoodMasterGroupEntityV2(val foodMasterId: String, val foodGroup: String)

@Entity(
    tableName = "foodMasterSeasons", primaryKeys = ["foodMasterId", "month"],
    foreignKeys = [ForeignKey(entity = FoodMasterEntityV2::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("month"), Index("foodMasterId")],
)
data class FoodMasterSeasonEntityV2(val foodMasterId: String, val month: Int)

@Entity(tableName = "shoppingLists", indices = [Index(value = ["weekStart"], unique = true), Index("sourceMenuUpdatedAt")])
data class ShoppingListEntityV2(@PrimaryKey val id: String, val weekStart: String, val sourceMenuUpdatedAt: String, val createdAt: String, val sourceFingerprint: String?, val needsRegeneration: Boolean)

@Entity(
    tableName = "shoppingItems",
    foreignKeys = [
        ForeignKey(entity = ShoppingListEntityV2::class, parentColumns = ["id"], childColumns = ["shoppingListId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FoodMasterEntityV2::class, parentColumns = ["id"], childColumns = ["foodMasterId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("shoppingListId"), Index("normalizedName"), Index("checked"), Index("excluded"), Index("foodMasterId"), Index(value = ["shoppingListId", "aggregationKey"], unique = true)],
)
data class ShoppingItemEntityV2(@PrimaryKey val id: String, val shoppingListId: String, val name: String, val normalizedName: String, val amount: String?, val unit: String, val recipeUsagesJson: String, val checked: Boolean, val excluded: Boolean, val foodMasterId: String?, val aggregationKey: String, val hasUnknownAmount: Boolean)

@Entity(tableName = "drafts", indices = [Index(value = ["kind", "targetKey"], unique = true)])
data class DraftEntityV2(@PrimaryKey val id: String, val kind: String, val targetKey: String, val payloadVersion: Int, val payloadJson: String, val baseRevisionsJson: String, val updatedAt: String)

@Entity(
    tableName = "draftReferences", primaryKeys = ["draftId", "entityType", "entityId"],
    foreignKeys = [ForeignKey(entity = DraftEntityV2::class, parentColumns = ["id"], childColumns = ["draftId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("draftId"), Index(value = ["entityType", "entityId"])],
)
data class DraftReferenceEntityV2(val draftId: String, val entityType: String, val entityId: String)

@Entity(tableName = "holidays", indices = [Index("year")])
data class HolidayEntityV2(@PrimaryKey val date: String, val name: String, val year: Int)

@Entity(tableName = "settings")
data class SettingEntityV2(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "metadata")
data class MetadataEntityV2(@PrimaryKey val key: String, val value: String)
