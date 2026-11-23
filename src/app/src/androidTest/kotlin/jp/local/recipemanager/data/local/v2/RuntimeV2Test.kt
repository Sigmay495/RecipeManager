package jp.local.recipemanager.data.local.v2

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.app.v2.AppViewModelV2
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.data.local.RecipeManagerDatabase
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import android.net.Uri
import java.io.File

@RunWith(AndroidJUnit4::class)
class RuntimeV2Test {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun firstBootInitializesPresetsAndHolidaysExactlyOnceWithoutSalesUnitReuse()=runBlocking {
        withDb { db->
            DatabaseRuntimeV2.initialize(context,db)
            val before=db.backupDao().snapshot()
            assertTrue(before.foods.isNotEmpty())
            assertTrue(before.foods.all { !it.userEditable && it.baseUnit.isEmpty() })
            assertTrue(before.groups.any { it.foodGroup=="GRAIN" })
            assertEquals(setOf(2026,2027),before.holidays.map { it.year }.toSet())
            DatabaseRuntimeV2.initialize(context,db)
            assertEquals(before,db.backupDao().snapshot())
        }
    }
    @Test fun presetConflictStopsInitializationAndRollsBackAllWrites()=runBlocking {
        withDb { db->
            DatabaseRuntimeV2.initialize(context,db)
            val candidate=FoodRepositoryV2(db).save(FoodInputV2("競合検証用"))
            val alias=db.backupDao().builtInAliases().first()
            db.openHelper.writableDatabase.execSQL("INSERT INTO userFoodAliases VALUES (?,?,?,?,?)",
                arrayOf(UUID.randomUUID().toString(),candidate.id,alias.alias,alias.normalizedAlias,"2026-11-22T00:00:00Z"))
            db.systemDao().putMetadata(MetadataEntityV2("food_master_version","force-recheck"))
            val before=db.backupDao().snapshot()
            assertTrue(runCatching { DatabaseRuntimeV2.initialize(context,db) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before,db.backupDao().snapshot())
        }
    }
    @Test fun runtimeBuilderMigratesTheExistingV1FileAndPreservesUserIdentifiers()=runBlocking {
        val name="runtime-test-${UUID.randomUUID()}.db"
        val recipe=UUID.randomUUID().toString()
        try {
            val old=Room.databaseBuilder(context,RecipeManagerDatabase::class.java,name).build()
            try {
                val sql=old.openHelper.writableDatabase
                sql.execSQL("INSERT INTO recipes VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    arrayOf(recipe,"移行料理","移行料理","ONE_DISH",null,"NORMAL",null,"保持",null,null,"","ACTIVE",null,"2026-11-22T00:00:00Z","2026-11-22T00:00:00Z"))
                sql.execSQL("INSERT INTO recipeIngredients VALUES (?,?,?,?,?,?,?,?,?)",
                    arrayOf<Any?>(UUID.randomUUID().toString(),recipe,0,"材料","材料","1","g","",null))
            } finally { old.close() }
            val upgraded=DatabaseRuntimeV2.build(context,name)
            try {
                DatabaseRuntimeV2.initialize(context,upgraded)
                assertEquals(recipe,upgraded.recipeDao().all().single().recipe.id)
                assertEquals("保持",upgraded.recipeDao().all().single().recipe.memo)
                assertEquals("1",upgraded.recipeDao().all().single().ingredients.single().amount)
                assertEquals(2,upgraded.openHelper.writableDatabase.version)
            } finally { upgraded.close() }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun uiDraftAddsNewFoodBaselineAndNavigationFlushesBeforeRecipeCommit()=runBlocking {
        withDb { db->
            val vm=AppViewModelV2(context.applicationContext as Application,db)
            val store=ViewModelStore().also { it.put("test",vm) }
            try {
                withTimeout(15000) { while(!vm.state.value.ready&&vm.state.value.error==null) delay(20) }
                assertNull(vm.state.value.error)
                val food=FoodRepositoryV2(db).save(FoodInputV2("画面材料"))
                vm.action { vm.refresh() }.join()
                vm.openRecipe().join()
                assertNull(vm.state.value.error)
                vm.formEdit("name","画面料理")
                vm.addIngredient(food)
                vm.flushDraft().join()
                assertNull(vm.state.value.error)
                val row=vm.state.value.editor!!.row!!
                val payload=JsonInput.mapper().readTree(row.payloadJson)
                assertEquals("画面料理",payload.path("form").path("name").asText())
                assertTrue(row.baseRevisionsJson.contains(food.id))
                vm.navigate("recipes").join()
                assertNull(vm.state.value.editor)
                vm.openDraft(db.draftDao().find(row.id)!!).join()
                vm.saveRecipe().join()
                assertNull(vm.state.value.error)
                assertEquals(1,db.recipeDao().all().size)
                assertTrue(db.draftDao().all().isEmpty())
            } finally { store.clear() }
        }
    }
    @Test fun uiBackupUriRoundTripClearsPendingScreenStateAndCancellationClearsPreview()=runBlocking {
        withDb { db->
            val vm=AppViewModelV2(context.applicationContext as Application,db)
            val store=ViewModelStore().also { it.put("test",vm) }
            val file=File(context.cacheDir,"ui-backup-${UUID.randomUUID()}.json")
            try {
                withTimeout(15000) { while(!vm.state.value.ready&&vm.state.value.error==null) delay(20) }
                assertNull(vm.state.value.error)
                val food=FoodRepositoryV2(db).save(FoodInputV2("往復材料",foodGroups=setOf("VEGETABLE")))
                vm.action { vm.refresh() }.join()
                vm.exportTo(Uri.fromFile(file)).join()
                assertNull(vm.state.value.error)
                assertTrue(file.length()>0)
                assertEquals("2.0",JsonInput.mapper().readTree(file).path("schemaVersion").asText())
                vm.readBackup(Uri.fromFile(file))
                withTimeout(10000) { while(vm.state.value.restorePreview==null&&vm.state.value.error==null) delay(20) }
                assertNull(vm.state.value.error)
                assertEquals(1,vm.state.value.restorePreview!!.counts["利用者材料"])
                vm.restore(); vm.confirm()!!.join()
                assertNull(vm.state.value.error)
                assertEquals("home",vm.state.value.route)
                assertNull(vm.state.value.editor);assertNull(vm.state.value.restorePreview)
                assertEquals(food.id,db.foodDao().search("往復材料").single().id)
                vm.readBackup(Uri.fromFile(file))
                withTimeout(10000) { while(vm.state.value.restorePreview==null&&vm.state.value.error==null) delay(20) }
                vm.cancelRead();assertNull(vm.state.value.restorePreview)
            } finally { store.clear();file.delete() }
        }
    }
    @Test fun uiImportUriCanBeEditedRevalidatedAndConfirmedWithoutOverwritingExistingRecipes()=runBlocking {
        withDb { db->
            val vm=AppViewModelV2(context.applicationContext as Application,db)
            val store=ViewModelStore().also { it.put("test",vm) }
            val file=File(context.cacheDir,"ui-import-${UUID.randomUUID()}.json")
            try {
                withTimeout(15000) { while(!vm.state.value.ready&&vm.state.value.error==null) delay(20) }
                file.writeText("""{"schemaVersion":"1.0","fileType":"recipe-import","recipes":[{"name":"読込料理","category":"one_dish","ingredients":[{"name":"米","amount":1,"unit":"合"}]}]}""",Charsets.UTF_8)
                vm.importFile(Uri.fromFile(file))
                withTimeout(10000) { while(vm.state.value.editor?.kind!="import"&&vm.state.value.error==null) delay(20) }
                assertNull(vm.state.value.error)
                val key=vm.state.value.editor!!.payload.path("entries").first().path("localKey").asText()
                vm.formEdit("name","修正後料理",key)
                vm.revalidateImport().join()
                assertNull(vm.state.value.error)
                val savedDraft=vm.state.value.editor!!.row!!
                vm.importFile(null)
                withTimeout(10000) { while(vm.state.value.editor!=null&&vm.state.value.error==null) delay(20) }
                assertNull(vm.state.value.importReview)
                assertEquals(savedDraft.payloadJson,db.draftDao().find(savedDraft.id)!!.payloadJson)
                vm.openDraft(db.draftDao().find(savedDraft.id)!!).join()
                vm.confirmImport(emptySet());vm.confirm()!!.join()
                assertNull(vm.state.value.error)
                assertEquals("修正後料理",db.recipeDao().all().single().recipe.name)
                assertTrue(db.draftDao().all().isEmpty())
            } finally { store.clear();file.delete() }
        }
    }
    @Test fun rapidUiEditsDuringQueuedAutosavesKeepNewestPayloadAndUpdateToken()=runBlocking {
        withDb { db->
            val vm=AppViewModelV2(context.applicationContext as Application,db)
            val store=ViewModelStore().also { it.put("test",vm) }
            try {
                withTimeout(15000) { while(!vm.state.value.ready&&vm.state.value.error==null) delay(20) }
                vm.openRecipe().join()
                val pending=mutableListOf<Job>()
                repeat(15) { index->
                    vm.formEdit("name","連続入力$index")
                    pending+=vm.flushDraft()
                    delay(2)
                }
                pending.forEach { it.join() }
                vm.flushDraft().join()
                assertNull(vm.state.value.error)
                val row=db.draftDao().all().single()
                assertEquals("連続入力14",JsonInput.mapper().readTree(row.payloadJson).path("form").path("name").asText())
                assertEquals(row.updatedAt,vm.state.value.editor!!.row!!.updatedAt)
            } finally { store.clear() }
        }
    }
    private suspend fun withDb(block:suspend(RecipeManagerDatabaseV2)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(context,RecipeManagerDatabaseV2::class.java).build()
        try { block(db) } finally { db.close() }
    }
}
