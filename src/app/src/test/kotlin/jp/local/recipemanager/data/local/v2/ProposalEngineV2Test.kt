package jp.local.recipemanager.data.local.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate

class ProposalEngineV2Test {
    private val engine = ProposalEngineV2()

    @Test
    fun fullCatalogMatchingFindsFiveAndSevenDaysWithoutFalseShortageOrDuplicates() {
        val recipes = listOf("MAIN", "SIDE", "SOUP").flatMap { role -> (0 until 20).map { recipe("$role-$it", role) } }
        for (needed in listOf(5, 7)) {
            val request = request(recipes, needed)
            val result = success(request)
            assertEquals(0, result.relaxationLevel)
            val items = result.days.flatMap { it.items }
            assertEquals(needed * 3, items.size)
            assertEquals(items.size, items.map { it.recipeId }.toSet().size)
            result.days.filterNot { it.noMeal }.forEach { assertEquals(setOf("MAIN", "SIDE", "SOUP"), it.items.map { item -> item.role }.toSet()) }
            engine.validate(request, result.days, result.relaxationLevel)
        }
    }

    @Test
    fun fiveHundredRecipesRemainFeasibleAndInputOrderDoesNotChangeSeededResult() {
        val recipes = listOf("ONE_DISH", "MAIN", "SIDE", "SOUP").flatMap { role -> (0 until 125).map { recipe("$role-${it.toString().padStart(3, '0')}", role) } }
        val request = request(recipes)
        val first = success(request)
        val second = success(request.copy(recipes = recipes.reversed()))
        assertEquals(0, first.relaxationLevel)
        assertEquals(first, second)
        assertEquals(first.days.flatMap { it.items }.size, first.days.flatMap { it.items }.map { it.recipeId }.toSet().size)
    }

    @Test
    fun recentHistoryAtZeroAndThirteenDaysOverridesHighPriorityButFourteenDoesNot() {
        val high = recipe("high", priority = "high")
        val normal = recipe("normal")
        for (offset in listOf(0L, 13L, 14L)) {
            val input = request(listOf(high, normal), 1).copy(history = listOf(week.minusDays(offset) to high.recipe.id))
            val actual = success(input)
            assertEquals(0, actual.relaxationLevel)
            assertEquals(if (offset < 14) "normal" else "high", actual.days.first().items.single().recipeId)
        }
    }

    @Test
    fun otherWeekPlansUseBothThirteenDayBoundariesAndAllowFourteen() {
        val recipes = listOf(recipe("high", priority = "high"), recipe("normal"))
        for (offset in listOf(-14L, -13L, 13L, 14L)) {
            val result = success(request(recipes, 1).copy(otherPlans = listOf(week.plusDays(offset) to "high")))
            assertEquals(if (kotlin.math.abs(offset) == 13L) "normal" else "high", result.days.first().items.single().recipeId)
            assertEquals(0, result.relaxationLevel)
        }
    }

    @Test
    fun everyActualHistoryEntryIsCheckedInsteadOfLastCookedCacheOrFutureHistory() {
        val high = recipe("high", priority = "high", cache = week.minusDays(100).toString())
        val normal = recipe("normal", cache = week.minusDays(1).toString())
        val result = success(request(listOf(high, normal), 1).copy(history = listOf(week.minusDays(100) to "high", week.minusDays(3) to "high", week.plusDays(1) to "normal")))
        assertEquals("normal", result.days.first().items.single().recipeId)
        assertEquals(0, result.relaxationLevel)
    }

    @Test
    fun higherPriorityCannotOverrideCompositionButPrecedesEffortAndNutrition() {
        val hardHigh = recipe("hard-high", priority = "high", effort = "HARD", minutes = 120)
        val easyNormal = recipe("easy-normal", effort = "EASY", groups = "GRAIN,MEAT,VEGETABLE")
        assertEquals("hard-high", success(request(listOf(hardHigh, easyNormal), 1)).days.first().items.single().recipeId)
        val wrongCategory = recipe("main-high", "MAIN", priority = "high")
        assertEquals("easy-normal", success(request(listOf(wrongCategory, easyNormal), 1)).days.first().items.single().recipeId)
    }

    @Test
    fun retiredFixedMainIsRetainedWhileOtherRolesAreProposed() {
        val input = request(listOf(recipe("retired", "MAIN", "retired"), recipe("side", "SIDE"), recipe("soup", "SOUP")), 1)
        val fixed = ProposalItemV2("retired", "MAIN", locked = true)
        val days = input.days.toMutableList().apply { set(0, first().copy(items = listOf(fixed))) }
        val result = success(input.copy(days = days))
        assertEquals(0, result.relaxationLevel)
        assertTrue(fixed in result.days.first().items)
        assertEquals(setOf("retired", "side", "soup"), result.days.first().items.map { it.recipeId }.toSet())
    }

    @Test
    fun retiredCandidatesNeverReappearEvenAfterFinalRelaxation() {
        val result = success(request(listOf(recipe("active"), recipe("retired", priority = "retired")), 2))
        assertEquals(4, result.relaxationLevel)
        assertEquals(setOf("active"), result.days.flatMap { it.items }.map { it.recipeId }.toSet())
        assertTrue(result.evaluation.warnings.any { it.contains("同じ週") })
        assertEquals(4, result.evaluation.warnings.count { it.contains("解除しました") || it.contains("警告扱いへ変更しました") })
    }

    @Test
    fun pastDaysTodayResultsAndUnconfiguredProtectedDayRemainExactlyUnchanged() {
        val recipes = listOf(recipe("past", priority = "retired"), recipe("today", priority = "retired"))
        val days = request(recipes, 3).days.toMutableList().apply {
            set(0, first().copy(items = listOf(ProposalItemV2("past", "ONE_DISH"))))
            set(2, get(2).copy(items = listOf(ProposalItemV2("today", "ONE_DISH")), protectedDay = true))
        }
        val result = success(request(recipes, 3).copy(days = days, today = week.plusDays(2)))
        assertEquals(days, result.days)
        assertEquals(0, result.relaxationLevel)
    }

    @Test
    fun repeatedFixedRecipeIsPreservedWithWarningWithoutUnnecessaryRelaxation() {
        val retired = recipe("retired", priority = "retired")
        val input = request(listOf(retired), 2)
        val days = input.days.map { if (it.noMeal) it else it.copy(items = listOf(ProposalItemV2("retired", "ONE_DISH", locked = true))) }
        val result = success(input.copy(days = days))
        assertEquals(days, result.days)
        assertEquals(0, result.relaxationLevel)
        assertTrue(result.evaluation.warnings.any { it.contains("同じ週") })
    }

    @Test
    fun allUnneededDaysNeedNoRecipesAndProduceNoNutritionWarnings() {
        val result = success(request(emptyList(), 0))
        assertTrue(result.days.all { it.noMeal && it.items.isEmpty() })
        assertEquals(0, result.evaluation.nutritionPenalty)
        assertTrue(result.evaluation.warnings.isEmpty())
    }

    @Test
    fun pastWeekMissingRecipesAndMissingCategoriesHaveDifferentReasons() {
        val empty = engine.propose(request(emptyList(), 1)) as ProposalResultV2.Failure
        assertEquals("no_recipes", empty.kind)
        val category = engine.propose(request(listOf(recipe("main", "MAIN")), 1)) as ProposalResultV2.Failure
        assertEquals("category", category.kind)
        val past = engine.propose(request(listOf(recipe("one")), 1).copy(today = week.plusWeeks(1))) as ProposalResultV2.Failure
        assertEquals("constraints", past.kind)
    }

    @Test
    fun validatorRejectsRetiredInsertionFixedChangesAndWeeklyDuplicatesBeforeFinalRelaxation() {
        val input = request(listOf(recipe("active"), recipe("retired", priority = "retired")), 2)
        val repeated = success(input)
        reject { engine.validate(input, repeated.days, 0) }
        val retired = repeated.days.toMutableList().apply { set(0, first().copy(items = listOf(ProposalItemV2("retired", "ONE_DISH")))) }
        reject { engine.validate(input, retired, 4) }
        val lockedInput = input.copy(days = input.days.toMutableList().apply { set(0, first().copy(items = listOf(ProposalItemV2("active", "ONE_DISH", true)))) })
        reject { engine.validate(lockedInput, repeated.days, 4) }
    }

    @Test
    fun nutritionAndEffortRetainSpecifiedDailyAndWeeklyPenaltyRules() {
        val recipes = listOf(recipe("easy", effort = "EASY", minutes = 120), recipe("hard", effort = "HARD", minutes = 120))
        val input = request(recipes, 2)
        val days = input.days.mapIndexed { index, day -> if (index < 2) day.copy(items = listOf(ProposalItemV2(if (index == 0) "easy" else "hard", "ONE_DISH"))) else day }
        val evaluation = engine.evaluate(input, days)
        assertEquals(200, evaluation.effortPenalty)
        assertEquals(25, evaluation.nutritionPenalty)
    }

    @Test
    fun wasteCountsOnlyUnsharedMajorFoodsAndSeasonUsesTargetMonthsWithoutUnitConversion() {
        val major = food("major", true, setOf(11))
        val minor = food("minor", false, setOf(11))
        val first = recipe("first").copy(ingredients = listOf(ingredient("first", "major", "1", "g"), ingredient("first", "minor", "100", "kg"), ingredient("first", null, "2", "ml")))
        val second = recipe("second").copy(ingredients = listOf(ingredient("second", "major", "1000", "kg")))
        val input = request(listOf(first, second), 2).copy(foods = listOf(major, minor))
        val days = input.days.mapIndexed { index, day -> if (index < 2) day.copy(items = listOf(ProposalItemV2(if (index == 0) "first" else "second", "ONE_DISH"))) else day }
        val evaluation = engine.evaluate(input, days)
        assertEquals(0, evaluation.wastePenalty)
        assertEquals(1, evaluation.seasonalScore)
        assertEquals(10, engine.evaluate(input, days.mapIndexed { index, day -> if (index == 1) day.copy(noMeal = true, items = emptyList()) else day }).wastePenalty)
    }

    @Test
    fun requestAndEntitiesAreNotMutatedByProposalAndSameWeekPlansAreNotOtherWeekExclusions() {
        val high = recipe("high", priority = "high")
        val input = request(listOf(high, recipe("normal")), 1).copy(otherPlans = listOf(week.plusDays(1) to "high"))
        val before = input.copy(days = input.days.toList(), recipes = input.recipes.toList())
        val result = success(input)
        assertEquals("high", result.days.first().items.single().recipeId)
        assertEquals(before, input)
        assertFalse(input.days.first().items.isNotEmpty())
    }

    private fun request(recipes: List<RecipeDetailsV2>, needed: Int = 7) = ProposalRequestV2(
        week, week, (0L..6L).map { ProposalDayV2(week.plusDays(it), noMeal = it >= needed) }, recipes, emptyList(), seed = 42L,
    )
    private fun success(input: ProposalRequestV2): ProposalResultV2.Success {
        val result = engine.propose(input)
        assertTrue("提案が失敗しました: $result", result is ProposalResultV2.Success)
        return result as ProposalResultV2.Success
    }
    private fun reject(block: () -> Unit) {
        try { block(); fail("不正な候補を拒否する必要があります") } catch (_: IllegalArgumentException) { }
    }
    private fun recipe(id: String, category: String = "ONE_DISH", priority: String = "normal", effort: String = "NORMAL", groups: String = "", minutes: Int? = null, cache: String? = null) =
        RecipeDetailsV2(RecipeEntityV2(id, id, id, category, minutes, effort, null, "", cache, groups, priority, 1, at, at), emptyList())
    private fun food(id: String, main: Boolean, months: Set<Int>) = FoodDetailsV2(
        FoodMasterEntityV2(id, id, id, "g", main, true, 1), emptyList(), emptyList(), setOf("VEGETABLE"), months,
    )
    private fun ingredient(recipe: String, food: String?, amount: String, unit: String) = RecipeIngredientEntityV2(
        "$recipe-$food", recipe, 0, food ?: "unknown", food ?: "unknown", amount, unit, "", food,
    )
    private companion object {
        val week: LocalDate = LocalDate.parse("2026-11-23")
        const val at = "2026-11-22T00:00:00Z"
    }
}
