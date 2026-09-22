package jp.local.recipemanager.feature.recipeimport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.data.local.BuiltInMasterLoader
import jp.local.recipemanager.data.local.RecipeManagerDatabase
import jp.local.recipemanager.domain.model.RecipeStatus
import jp.local.recipemanager.feature.recipe.RecipeRepository
import jp.local.recipemanager.feature.recipe.RecipeDraft
import jp.local.recipemanager.feature.recipe.IngredientDraft
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecipeImportTest {
    private lateinit var context: Context
    private lateinit var database: RecipeManagerDatabase
    private lateinit var parser: RecipeImportParser
    private lateinit var service: RecipeImportService

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, RecipeManagerDatabase::class.java).build()
        BuiltInMasterLoader(context).ensureLoaded(database)
        parser = RecipeImportParser(context)
        val repository = RecipeRepository(database.recipeDao(), database.foodMasterDao())
        service = RecipeImportService(database, repository, database.foodMasterDao())
    }

    @After fun tearDown() = database.close()

    @Test fun validFileIsParsedAndImported() = runBlocking {
        val parsed = parser.parse("valid.json", validFile("鮭の塩焼き").toByteArray()) as ImportLoadResult.Success
        val preview = service.detectDuplicates(parsed.preview)
        val result = service.import(preview)
        assertEquals(1, result.added)
        assertEquals("鮭の塩焼き", database.recipeDao().getAll().single().recipe.name)
        assertTrue(database.recipeDao().getAll().single().recipe.nutritionGroups.isNotEmpty())
    }

    @Test fun invalidRecipeIsLeftAsErrorWhileValidRecipeRemainsImportable() {
        val json = """{
          "schemaVersion":"1.0","fileType":"recipe-import","recipes":[
            ${recipe("肉じゃが")},
            {"name":"","category":"main","ingredients":[]}
          ]
        }""".trimIndent()
        val preview = (parser.parse("partial.json", json.toByteArray()) as ImportLoadResult.Success).preview
        assertEquals(1, preview.validCount)
        assertEquals(1, preview.errorCount)
    }

    @Test fun invalidEnvelopeStopsWholeImport() {
        val result = parser.parse("bad.json", """{"schemaVersion":"9","fileType":"recipe-import","recipes":[]}""".toByteArray())
        assertTrue(result is ImportLoadResult.Failure)
    }

    @Test fun deletedDuplicateDefaultsToRestoreWithoutOverwriting() = runBlocking {
        val initial = (parser.parse("first.json", validFile("豆腐のみそ汁").toByteArray()) as ImportLoadResult.Success).preview
        service.import(service.detectDuplicates(initial))
        val existing = database.recipeDao().getAll().single()
        database.recipeDao().markDeleted(existing.recipe.id, existing.recipe.updatedAt.plusSeconds(1))

        val changedJson = validFile("豆腐のみそ汁").replace("メモ", "変更されたメモ")
        val duplicate = service.detectDuplicates((parser.parse("second.json", changedJson.toByteArray()) as ImportLoadResult.Success).preview)
        assertEquals(ImportAction.RESTORE, duplicate.candidates.single().action)
        val result = service.import(duplicate)
        assertEquals(1, result.restored)
        val restored = database.recipeDao().getAll().single().recipe
        assertEquals(RecipeStatus.ACTIVE, restored.status)
        assertEquals("メモ", restored.memo)
    }

    @Test fun unexpectedFailureRollsBackRecipesAndUserMasters() = runBlocking {
        val good = RecipeDraft(name = "未知食材料理", ingredients = listOf(IngredientDraft(name = "未知食材", amount = "1", unit = "個")))
        val bad = RecipeDraft(name = "", ingredients = listOf(IngredientDraft(name = "鮭")))
        val preview = RecipeImportPreview(
            "rollback.json",
            listOf(
                RecipeImportCandidate(0, good, emptyList(), emptyList(), action = ImportAction.ADD),
                RecipeImportCandidate(1, bad, emptyList(), emptyList(), action = ImportAction.ADD),
            ),
        )
        assertTrue(runCatching { service.import(preview) }.isFailure)
        assertTrue(database.recipeDao().getAll().isEmpty())
        assertEquals(null, database.foodMasterDao().findIdByName("未知食材"))
    }

    @Test fun oneHundredRecipesAreAcceptedAndOneHundredOneAreRejected() {
        fun file(count: Int) = """{"schemaVersion":"1.0","fileType":"recipe-import","recipes":[${(1..count).joinToString { recipe("料理$it") }}]}"""
        val hundred = parser.parse("100.json", file(100).toByteArray())
        val hundredOne = parser.parse("101.json", file(101).toByteArray())
        assertTrue(hundred is ImportLoadResult.Success)
        assertEquals(100, (hundred as ImportLoadResult.Success).preview.validCount)
        assertTrue(hundredOne is ImportLoadResult.Failure)
    }

    private fun validFile(name: String) = """{"schemaVersion":"1.0","fileType":"recipe-import","recipes":[${recipe(name)}]}"""

    private fun recipe(name: String) = """{
      "name":"$name","category":"main",
      "ingredients":[{"name":"鮭","amount":2,"unit":"切れ","note":""}],
      "cookingTimeMinutes":20,"effortLevel":"normal","sourceUrl":"https://example.com/recipe","memo":"メモ","warnings":[]
    }""".trimIndent()
}
