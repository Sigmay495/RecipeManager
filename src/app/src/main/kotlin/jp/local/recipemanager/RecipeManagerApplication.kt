package jp.local.recipemanager

import android.app.Application
import jp.local.recipemanager.data.local.BuiltInMasterLoader
import jp.local.recipemanager.data.local.BuiltInHolidayLoader
import jp.local.recipemanager.data.local.RecipeManagerDatabase
import jp.local.recipemanager.data.local.v2.DatabaseRuntimeV2
import jp.local.recipemanager.data.local.v2.RecipeManagerDatabaseV2

class RecipeManagerApplication : Application() {
    // Kept only for legacy regression helpers. The activity never opens the v1 connection.
    val database: RecipeManagerDatabase by lazy { RecipeManagerDatabase.build(this) }
    val databaseV2: RecipeManagerDatabaseV2 by lazy { DatabaseRuntimeV2.build(this) }
}
