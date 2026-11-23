package jp.local.recipemanager.data.local.v2

import androidx.room.Database
import androidx.room.RoomDatabase

/** Staged v2 schema. The application stays on its v1 database until its repositories are ready. */
@Database(
    entities = [
        RecipeEntityV2::class, RecipeIngredientEntityV2::class, WeeklyMenuEntityV2::class,
        MenuDayEntityV2::class, MenuItemEntityV2::class, CookingHistoryEntityV2::class,
        HistoryItemEntityV2::class, ShoppingListEntityV2::class, ShoppingItemEntityV2::class,
        FoodMasterEntityV2::class, FoodMasterAliasEntityV2::class, FoodMasterGroupEntityV2::class,
        FoodMasterSeasonEntityV2::class, HolidayEntityV2::class, SettingEntityV2::class,
        MetadataEntityV2::class, PlanOutcomeEntityV2::class, PostponedPlanEntityV2::class,
        UserFoodAliasEntityV2::class, DraftEntityV2::class, DraftReferenceEntityV2::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class RecipeManagerDatabaseV2 : RoomDatabase() {
    abstract fun referenceDao(): ReferenceDaoV2
    abstract fun foodDao(): FoodDaoV2
    abstract fun recipeDao(): RecipeDaoV2
    abstract fun menuDao(): MenuDaoV2
    abstract fun historyDao(): HistoryDaoV2
    abstract fun proposalReadDao(): ProposalReadDaoV2
    abstract fun draftDao(): DraftDaoV2
    abstract fun shoppingDao(): ShoppingDaoV2
    abstract fun backupDao(): BackupDaoV2
    abstract fun systemDao(): SystemDaoV2
}
