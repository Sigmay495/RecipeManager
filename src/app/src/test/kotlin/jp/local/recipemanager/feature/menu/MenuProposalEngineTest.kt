package jp.local.recipemanager.feature.menu

import jp.local.recipemanager.data.local.RecipeEntity
import jp.local.recipemanager.data.local.RecipeWithIngredients
import jp.local.recipemanager.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.system.measureTimeMillis

class MenuProposalEngineTest {
    private val monday = LocalDate.of(2026, 9, 28)

    @Test fun proposalAvoidsRecipesCookedWithinTwoWeeks() {
        val recent = recipe("recent", lastCooked = monday.minusDays(3))
        val candidates = listOf(recent) + (1..5).map { recipe("recipe-$it") }
        val result = requireNotNull(MenuProposalEngine(emptyList(), emptyMap()).propose((0L..4L).map(monday::plusDays), candidates, 1))
        assertFalse(result.days.flatMap { it.dishes }.any { it.recipe.recipe.id == recent.recipe.id })
        assertEquals(0, result.relaxationLevel)
    }

    @Test fun proposalRelaxesDuplicateRuleWhenRecipesAreInsufficient() {
        val result = requireNotNull(MenuProposalEngine(emptyList(), emptyMap()).propose((0L..4L).map(monday::plusDays), listOf(recipe("only")), 1))
        assertEquals(4, result.relaxationLevel)
        assertTrue(result.evaluation.warnings.any { "重複" in it })
        assertEquals(5, result.days.size)
    }

    @Test fun evaluationWarnsAboutMissingNutritionGroups() {
        val day = ProposedDay(monday, listOf(ProposedDish(MenuRole.ONE_DISH, recipe("plain"))))
        val evaluation = MenuProposalEngine(emptyList(), emptyMap()).evaluate(listOf(day))
        assertEquals(5 + 5 + 5 + 1 + 2 + 2, evaluation.nutritionPenalty)
        assertTrue(evaluation.warnings.any { "主食" in it })
        assertTrue(evaluation.warnings.any { "たんぱく質" in it })
        assertTrue(evaluation.warnings.any { "野菜" in it })
    }

    @Test fun hardDaysInARowHaveLargeEffortPenalty() {
        val days = listOf(
            ProposedDay(monday, listOf(ProposedDish(MenuRole.ONE_DISH, recipe("hard-1", EffortLevel.HARD)))),
            ProposedDay(monday.plusDays(1), listOf(ProposedDish(MenuRole.ONE_DISH, recipe("hard-2", EffortLevel.HARD)))),
        )
        val evaluation = MenuProposalEngine(emptyList(), emptyMap()).evaluate(days)
        assertTrue(evaluation.effortPenalty >= 1000)
        assertTrue(evaluation.warnings.any { "手間" in it })
    }

    @Test fun fiveHundredRecipesAreProposedWithinFiveSeconds() {
        val recipes = RecipeCategory.entries.flatMap { category -> (1..125).map { index -> recipe("${category.name}-$index", category = category) } }
        lateinit var result: MenuProposal
        val elapsed = measureTimeMillis {
            result = requireNotNull(MenuProposalEngine(emptyList(), emptyMap()).propose((0L..4L).map(monday::plusDays), recipes, 1234))
        }
        assertEquals(5, result.days.size)
        assertTrue("elapsed=${elapsed}ms", elapsed < 5_000)
    }

    private fun recipe(id: String, effort: EffortLevel = EffortLevel.NORMAL, lastCooked: LocalDate? = null, category: RecipeCategory = RecipeCategory.ONE_DISH) = RecipeWithIngredients(
        RecipeEntity(id, id, id, category, 20, effort, null, "", lastCooked, null, emptySet(), RecipeStatus.ACTIVE, null, Instant.EPOCH, Instant.EPOCH),
        emptyList(),
    )
}
