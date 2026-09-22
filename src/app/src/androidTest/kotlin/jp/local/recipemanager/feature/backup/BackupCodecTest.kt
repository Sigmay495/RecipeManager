package jp.local.recipemanager.feature.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import jp.local.recipemanager.data.local.*
import jp.local.recipemanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupCodecTest {
    private val codec by lazy { BackupCodec(ApplicationProvider.getApplicationContext<Context>()) }
    private val now = Instant.parse("2026-09-21T00:00:00Z")

    @Test fun generatedBackupCanBeParsedWithoutLosingRecipeData() {
        val bytes = codec.encode(rows(), now, "0.1.0", "1.0.0")
        val parsed = codec.parse(bytes)
        assertTrue(parsed is BackupParseResult.Success)
        val backup = (parsed as BackupParseResult.Success).backup
        assertEquals("recipe-1", backup.rows.recipes.single().id)
        assertEquals(BigDecimal("200"), backup.rows.ingredients.single().amount)
        assertEquals(now, backup.createdAt)
    }

    @Test fun brokenRecipeReferenceIsRejectedBeforeRestore() {
        val json = codec.encode(rows(), now, "0.1.0", "1.0.0").toString(Charsets.UTF_8)
            .replace("\"recipeId\" : \"recipe-1\"", "\"recipeId\" : \"missing\"")
        val result = codec.parse(json.toByteArray())
        assertTrue(result is BackupParseResult.Failure)
        assertTrue((result as BackupParseResult.Failure).message.contains("参照"))
    }

    @Test fun unsupportedNewerFormatIsRejected() {
        val json = codec.encode(rows(), now, "0.1.0", "1.0.0").toString(Charsets.UTF_8).replace("\"schemaVersion\" : \"1.0\"", "\"schemaVersion\" : \"2.0\"")
        assertTrue(codec.parse(json.toByteArray()) is BackupParseResult.Failure)
    }

    private fun rows(): BackupRows {
        val recipe = RecipeEntity("recipe-1", "豚肉炒め", "豚肉炒め", RecipeCategory.MAIN, 20, EffortLevel.NORMAL, null, "", null, null, setOf(FoodGroup.MEAT), RecipeStatus.ACTIVE, null, now, now)
        val ingredient = RecipeIngredientEntity("ingredient-1", recipe.id, 0, "豚肉", "豚肉", BigDecimal("200"), "g", "", null)
        return BackupRows(listOf(recipe), listOf(ingredient), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), listOf(SettingEntity("backupReminderIntervalDays", "30")))
    }
}
