package jp.local.recipemanager.feature.recipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecipeValidatorTest {
    private val valid = RecipeDraft(
        name = "肉じゃが",
        ingredients = listOf(IngredientDraft(name = "じゃがいも", amount = "2", unit = "個")),
        cookingTime = "30",
        sourceUrl = "https://example.com/nikujaga",
    )

    @Test fun acceptsValidRecipe() {
        assertTrue(RecipeValidator.validate(valid, LocalDate.of(2026, 9, 21)).isEmpty())
    }

    @Test fun rejectsInvalidRequiredValuesRangesAndUrl() {
        val errors = RecipeValidator.validate(
            valid.copy(
                name = "",
                ingredients = listOf(IngredientDraft(name = "", amount = "0")),
                cookingTime = "1441",
                initialLastCookedAt = "2026-09-22",
                sourceUrl = "file:///recipe.txt",
            ),
            LocalDate.of(2026, 9, 21),
        )
        assertFalse(errors.isEmpty())
        assertTrue("name" in errors)
        assertTrue("ingredient.0.name" in errors)
        assertTrue("ingredient.0.amount" in errors)
        assertTrue("cookingTime" in errors)
        assertTrue("initialLastCookedAt" in errors)
        assertTrue("sourceUrl" in errors)
    }
}
