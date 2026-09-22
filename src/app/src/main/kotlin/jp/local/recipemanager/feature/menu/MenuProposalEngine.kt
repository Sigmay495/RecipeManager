package jp.local.recipemanager.feature.menu

import jp.local.recipemanager.data.local.*
import jp.local.recipemanager.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class ProposedDish(val role: MenuRole, val recipe: RecipeWithIngredients)
data class ProposedDay(val date: LocalDate, val dishes: List<ProposedDish>)
data class MenuEvaluation(
    val warnings: List<String>,
    val effortPenalty: Int,
    val recentCount: Int,
    val nutritionPenalty: Int,
    val wastePenalty: Int,
    val seasonalScore: Int,
)
data class MenuProposal(val days: List<ProposedDay>, val evaluation: MenuEvaluation, val relaxationLevel: Int)

class MenuProposalEngine(private val masters: List<FoodMasterWithDetails>, private val historyCounts: Map<String, Int>) {
    private val masterById = masters.associateBy { it.master.id }

    fun propose(dates: List<LocalDate>, recipes: List<RecipeWithIngredients>, seed: Long): MenuProposal? {
        if (dates.isEmpty()) return MenuProposal(emptyList(), MenuEvaluation(emptyList(), 0, 0, 0, 0, 0), 0)
        if (recipes.none { it.recipe.category == RecipeCategory.ONE_DISH } &&
            RecipeCategory.entries.filter { it != RecipeCategory.ONE_DISH }.any { category -> recipes.none { it.recipe.category == category } }) return null

        for (level in 0..4) {
            val result = search(dates, recipes, seed, level)
            if (result != null) return result
        }
        return null
    }

    fun evaluate(days: List<ProposedDay>, relaxationLevel: Int = 0): MenuEvaluation {
        val warnings = mutableListOf<String>()
        val burdens = days.map { day -> burden(day) }
        val hardAdjacent = days.zipWithNext().count { (a, b) -> a.dishes.any { it.recipe.recipe.effortLevel == EffortLevel.HARD } && b.dishes.any { it.recipe.recipe.effortLevel == EffortLevel.HARD } }
        val effort = if (burdens.isEmpty()) 0 else ((burdens.max() - burdens.min()) * 100).roundToInt() + hardAdjacent * 1000
        if (hardAdjacent > 0) warnings += "手間が大きい日が連続しています"

        val recent = days.sumOf { day -> day.dishes.count { dish -> recentlyCooked(dish.recipe.recipe, day.date) } }
        if (recent > 0) warnings += "2週間以内に作った料理が${recent}件含まれます"
        val repeated = days.flatMap { it.dishes }.groupingBy { it.recipe.recipe.id }.eachCount().filterValues { it > 1 }
        if (repeated.isNotEmpty()) warnings += "同じ週に重複する料理があります"

        var nutrition = 0
        val missingByGroup = mutableMapOf<NutritionGroup, Int>()
        days.forEach { day ->
            val present = nutritionGroups(day)
            NutritionGroup.entries.forEach { group -> if (group !in present) {
                nutrition += if (group == NutritionGroup.STAPLE) 1 else 2
                missingByGroup[group] = (missingByGroup[group] ?: 0) + 1
                warnings += "${day.date}: ${group.label}が不足しています"
            } }
        }
        missingByGroup.filterValues { it * 2 >= days.size.coerceAtLeast(1) }.forEach { (group, _) -> nutrition += 5; warnings += "週全体で${group.label}が不足しています" }

        val ingredients = days.flatMap { it.dishes }.flatMap { it.recipe.ingredients }
        val grouped = ingredients.filter { it.foodMasterId != null }.groupBy { it.foodMasterId!! }
        var waste = grouped.values.sumOf { entries ->
            val master = masterById[entries.first().foodMasterId]?.master ?: return@sumOf 0
            val amounts = entries.mapNotNull { baseAmount(it) }
            val remainder = if (amounts.isEmpty()) 0 else {
                val pack = standardPack(master.salesUnit)
                if (pack == null) 0 else (((pack - amounts.fold(BigDecimal.ZERO, BigDecimal::add).remainder(pack)).remainder(pack)).divide(pack, 4, RoundingMode.HALF_UP).toDouble() * 100).roundToInt()
            }
            remainder + if (master.isMainIngredient && entries.map { it.recipeId }.distinct().size == 1) 10 else 0
        }
        if (waste >= 100) warnings += "使い切りにくい食材が多い献立です"
        val seasonal = grouped.keys.count { id -> masterById[id]?.seasons?.any { season -> days.any { it.date.monthValue == season.month } } == true }
        if (relaxationLevel > 0) warnings.add(0, "提案条件を${relaxationLevel}段階緩和しました")
        return MenuEvaluation(warnings.distinct(), effort, recent, nutrition, waste, seasonal)
    }

    private fun search(dates: List<LocalDate>, recipes: List<RecipeWithIngredients>, seed: Long, level: Int): MenuProposal? {
        var beam = listOf<List<ProposedDay>>(emptyList())
        dates.forEach { date ->
            val candidates = dailyCandidates(date, recipes, level)
            if (candidates.isEmpty()) return null
            beam = beam.flatMap { partial -> candidates.asSequence().filter { candidate ->
                level >= 4 || candidate.dishes.none { dish -> partial.flatMap { it.dishes }.any { it.recipe.recipe.id == dish.recipe.recipe.id } }
            }.map { partial + it }.toList() }
                .sortedWith(compareByScore(level, seed)).take(BEAM_WIDTH)
            if (beam.isEmpty()) return null
        }
        val best = beam.minWithOrNull(compareByScore(level, seed)) ?: return null
        return MenuProposal(best, evaluate(best, level), level)
    }

    private fun dailyCandidates(date: LocalDate, recipes: List<RecipeWithIngredients>, level: Int): List<ProposedDay> {
        fun ranked(category: RecipeCategory) = recipes.asSequence().filter { it.recipe.category == category }
            .filter { level >= 4 || !recentlyCooked(it.recipe, date) }
            .sortedWith(compareBy<RecipeWithIngredients>({ it.recipe.effortLevel.ordinal }, { -seasonCount(it, date.monthValue) }, { historyCounts[it.recipe.id] ?: 0 }, { it.recipe.normalizedName }))
            .take(20).toList()
        val one = ranked(RecipeCategory.ONE_DISH).map { ProposedDay(date, listOf(ProposedDish(MenuRole.ONE_DISH, it))) }
        val main = ranked(RecipeCategory.MAIN); val side = ranked(RecipeCategory.SIDE); val soup = ranked(RecipeCategory.SOUP)
        val three = main.flatMap { m -> side.flatMap { s -> soup.map { p -> ProposedDay(date, listOf(ProposedDish(MenuRole.MAIN, m), ProposedDish(MenuRole.SIDE, s), ProposedDish(MenuRole.SOUP, p))) } } }
            .sortedBy { burden(it) }.take(100)
        return (one + three).sortedBy { burden(it) }.take(120)
    }

    private fun compareByScore(level: Int, seed: Long) = Comparator<List<ProposedDay>> { a, b ->
        val ea = evaluate(a); val eb = evaluate(b)
        val ta = listOf(ea.effortPenalty, if (level >= 4) 0 else ea.recentCount, if (level >= 3) 0 else ea.nutritionPenalty, if (level >= 2) 0 else ea.wastePenalty, if (level >= 1) 0 else -ea.seasonalScore, a.sumOf(::historyCount), stableRandom(a, seed))
        val tb = listOf(eb.effortPenalty, if (level >= 4) 0 else eb.recentCount, if (level >= 3) 0 else eb.nutritionPenalty, if (level >= 2) 0 else eb.wastePenalty, if (level >= 1) 0 else -eb.seasonalScore, b.sumOf(::historyCount), stableRandom(b, seed))
        ta.zip(tb).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }

    private fun burden(day: ProposedDay): Double = day.dishes.sumOf { it.recipe.recipe.effortLevel.ordinal + 1 }.toDouble() + (day.dishes.sumOf { it.recipe.recipe.cookingTimeMinutes ?: 0 } / 60.0 * .5).coerceAtMost(2.0)
    private fun recentlyCooked(recipe: RecipeEntity, date: LocalDate) = recipe.lastCookedAt?.let { !it.isAfter(date) && ChronoUnit.DAYS.between(it, date) < 14 } == true
    private fun nutritionGroups(day: ProposedDay): Set<NutritionGroup> = buildSet {
        if (day.dishes.size == 3) add(NutritionGroup.STAPLE)
        val groups = day.dishes.flatMap { it.recipe.recipe.nutritionGroups }
        if (FoodGroup.GRAIN in groups) add(NutritionGroup.STAPLE)
        if (groups.any { it in setOf(FoodGroup.MEAT, FoodGroup.SEAFOOD, FoodGroup.EGG, FoodGroup.SOY) }) add(NutritionGroup.PROTEIN)
        if (groups.any { it in setOf(FoodGroup.VEGETABLE, FoodGroup.MUSHROOM, FoodGroup.SEAWEED) }) add(NutritionGroup.VEGETABLE)
    }
    private fun seasonCount(recipe: RecipeWithIngredients, month: Int) = recipe.ingredients.mapNotNull { it.foodMasterId }.distinct().count { id -> masterById[id]?.seasons?.any { it.month == month } == true }
    private fun historyCount(day: ProposedDay) = day.dishes.sumOf { historyCounts[it.recipe.recipe.id] ?: 0 }
    private fun stableRandom(days: List<ProposedDay>, seed: Long) = (days.flatMap { it.dishes }.fold(seed) { value, dish -> value * 31 + dish.recipe.recipe.id.hashCode() } and Int.MAX_VALUE.toLong()).toInt()
    private fun baseAmount(item: RecipeIngredientEntity): BigDecimal? = item.amount?.let { amount -> when (item.unit.lowercase()) { "kg", "l" -> amount * BigDecimal(1000); "g", "ml" -> amount; else -> amount } }
    private fun standardPack(unit: String): BigDecimal? = when (unit.lowercase()) { "g" -> BigDecimal(500); "kg" -> BigDecimal(1000); "ml" -> BigDecimal(1000); "l" -> BigDecimal(1000); "個", "本", "枚", "袋", "パック", "束", "丁", "玉", "切れ" -> BigDecimal.ONE; else -> null }

    private enum class NutritionGroup(val label: String) { STAPLE("主食"), PROTEIN("たんぱく質"), VEGETABLE("野菜") }
    private companion object { const val BEAM_WIDTH = 50 }
}
