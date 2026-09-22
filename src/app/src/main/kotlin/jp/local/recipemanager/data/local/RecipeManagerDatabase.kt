package jp.local.recipemanager.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        RecipeEntity::class, RecipeIngredientEntity::class, WeeklyMenuEntity::class,
        MenuDayEntity::class, MenuItemEntity::class, CookingHistoryEntity::class,
        HistoryItemEntity::class, ShoppingListEntity::class, ShoppingItemEntity::class,
        FoodMasterEntity::class, FoodMasterAliasEntity::class, FoodMasterGroupEntity::class,
        FoodMasterSeasonEntity::class, HolidayEntity::class, SettingEntity::class, MetadataEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(RoomConverters::class)
abstract class RecipeManagerDatabase : RoomDatabase() {
    abstract fun recipeDao(): RecipeDao
    abstract fun foodMasterDao(): FoodMasterDao
    abstract fun menuDao(): MenuDao
    abstract fun historyDao(): HistoryDao
    abstract fun shoppingDao(): ShoppingDao
    abstract fun systemDataDao(): SystemDataDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val DATABASE_NAME = "recipe-manager.db"

        fun build(context: Context): RecipeManagerDatabase = Room.databaseBuilder(
            context.applicationContext,
            RecipeManagerDatabase::class.java,
            DATABASE_NAME,
        ).build()
    }
}
