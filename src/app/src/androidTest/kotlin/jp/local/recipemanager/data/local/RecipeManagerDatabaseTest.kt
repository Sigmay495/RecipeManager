package jp.local.recipemanager.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.domain.model.EffortLevel
import jp.local.recipemanager.domain.model.RecipeCategory
import jp.local.recipemanager.domain.model.RecipeStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RecipeManagerDatabaseTest {
    private lateinit var database: RecipeManagerDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            RecipeManagerDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun recipeAndIngredientsAreSavedAndSoftDeletedTogether() = runBlocking {
        val now = Instant.parse("2026-09-20T00:00:00Z")
        val recipe = RecipeEntity(
            id = "recipe-1",
            name = "豚肉と玉ねぎ炒め",
            normalizedName = TextNormalizer.normalize("豚肉と玉ねぎ炒め"),
            category = RecipeCategory.MAIN,
            cookingTimeMinutes = 20,
            effortLevel = EffortLevel.NORMAL,
            sourceUrl = null,
            memo = "",
            lastCookedAt = null,
            initialLastCookedAt = null,
            nutritionGroups = emptySet(),
            status = RecipeStatus.ACTIVE,
            deletedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        val ingredient = RecipeIngredientEntity(
            id = "ingredient-1",
            recipeId = recipe.id,
            displayOrder = 0,
            name = "豚肉",
            normalizedName = "豚肉",
            amount = null,
            unit = "g",
            note = "",
            foodMasterId = null,
        )

        database.recipeDao().insert(recipe, listOf(ingredient))
        assertEquals(1, database.recipeDao().findById(recipe.id)?.ingredients?.size)

        database.recipeDao().markDeleted(recipe.id, now.plusSeconds(1))
        assertEquals(RecipeStatus.DELETED, database.recipeDao().findById(recipe.id)?.recipe?.status)
    }

    @Test
    fun builtInMasterIsLoadedOnceAndAliasesCanBeResolved() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = BuiltInMasterLoader(context)

        loader.ensureLoaded(database)
        val firstCount = database.foodMasterDao().builtInCount()
        loader.ensureLoaded(database)

        assertEquals(18, firstCount)
        assertEquals(firstCount, database.foodMasterDao().builtInCount())
        assertNotNull(database.foodMasterDao().findIdByName("　豚バラ肉 "))
        assertNull(database.foodMasterDao().findIdByName("未登録食材"))
    }

    @Test
    fun builtInJapaneseHolidaysAreLoadedOnce() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val loader = BuiltInHolidayLoader(context)
        loader.ensureLoaded(database)
        loader.ensureLoaded(database)

        val holidays = database.systemDataDao().holidays(2026)
        assertEquals(18, holidays.size)
        assertEquals("秋分の日", holidays.first { it.date.toString() == "2026-09-23" }.name)
    }

    @Test
    fun fullRestoreReplacesUserDataAndUsesBackupCreatedAt() = runBlocking {
        val at = Instant.parse("2026-09-21T01:02:03Z")
        val recipe = RecipeEntity("restored", "復元料理", "復元料理", RecipeCategory.MAIN, 10, EffortLevel.EASY, null, "", null, null, emptySet(), RecipeStatus.ACTIVE, null, at, at)
        val rows = BackupRows(listOf(recipe), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(SettingEntity("backupReminderIntervalDays", "14")))

        database.backupDao().replace(rows, at)

        assertNotNull(database.recipeDao().findById("restored"))
        assertEquals("14", database.systemDataDao().getSetting("backupReminderIntervalDays"))
        assertEquals(at.toString(), database.systemDataDao().getSetting("lastBackupAt"))
    }
}
