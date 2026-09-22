package jp.local.recipemanager

import android.app.Application
import jp.local.recipemanager.data.local.BuiltInMasterLoader
import jp.local.recipemanager.data.local.BuiltInHolidayLoader
import jp.local.recipemanager.data.local.RecipeManagerDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RecipeManagerApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: RecipeManagerDatabase by lazy { RecipeManagerDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            BuiltInMasterLoader(this@RecipeManagerApplication).ensureLoaded(database)
            BuiltInHolidayLoader(this@RecipeManagerApplication).ensureLoaded(database)
        }
    }
}
