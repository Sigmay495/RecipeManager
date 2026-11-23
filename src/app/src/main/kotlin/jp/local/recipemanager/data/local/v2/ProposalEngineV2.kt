package jp.local.recipemanager.data.local.v2

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

data class ProposalItemV2(val recipeId: String, val role: String, val locked: Boolean = false)
data class ProposalDayV2(
    val date: LocalDate,
    val noMeal: Boolean,
    val dayLocked: Boolean = false,
    val items: List<ProposalItemV2> = emptyList(),
    val protectedDay: Boolean = false,
)
data class ProposalRequestV2(
    val weekStart: LocalDate,
    val today: LocalDate,
    val days: List<ProposalDayV2>,
    val recipes: List<RecipeDetailsV2>,
    val foods: List<FoodDetailsV2>,
    val history: List<Pair<LocalDate, String>> = emptyList(),
    val otherPlans: List<Pair<LocalDate, String>> = emptyList(),
    val seed: Long,
)
data class ProposalEvaluationV2(
    val warnings: List<String>,
    val lowCount: Int,
    val normalCount: Int,
    val effortPenalty: Int,
    val recentCount: Int,
    val nutritionPenalty: Int,
    val wastePenalty: Int,
    val seasonalScore: Int,
    val historicalCount: Int,
)
sealed interface ProposalResultV2 {
    data class Success(val days: List<ProposalDayV2>, val evaluation: ProposalEvaluationV2, val relaxationLevel: Int, val seed: Long) : ProposalResultV2
    data class Failure(val kind: String, val message: String) : ProposalResultV2
}

/** Pure proposal search. Feasibility uses every recipe; bounded optimization never decides scarcity. */
class ProposalEngineV2 {
    fun propose(request: ProposalRequestV2): ProposalResultV2 = try {
        val context = Context(request)
        context.checkRequest()
        val patterns = context.patterns()
        val needsCandidates = patterns.any { pattern -> pattern.any { it.roles.isNotEmpty() } }
        if (needsCandidates && context.active.isEmpty()) {
            ProposalResultV2.Failure("no_recipes", "提案に利用できるレシピがありません")
        } else {
            val strict = patterns.mapNotNull { context.witness(it, relaxed = false) }
            val level = if (strict.isNotEmpty()) 0 else 4
            val witnesses = if (strict.isNotEmpty()) strict else patterns.mapNotNull { context.witness(it, relaxed = true) }
            if (witnesses.isEmpty()) {
                ProposalResultV2.Failure("category", "献立構成に必要なカテゴリのレシピが不足しています")
            } else {
                val best = context.optimize(witnesses, level)
                validate(request, best, level)
                ProposalResultV2.Success(best, context.evaluate(best, level), level, request.seed)
            }
        }
    } catch (e: IllegalArgumentException) {
        ProposalResultV2.Failure("constraints", e.message ?: "提案条件が不正です")
    } catch (e: IllegalStateException) {
        ProposalResultV2.Failure("search_error", e.message ?: "献立の探索に失敗しました")
    }

    /** Shared pre-adoption validation; only level 4 may relax recent/weekly duplication. */
    fun validate(request: ProposalRequestV2, days: List<ProposalDayV2>, relaxationLevel: Int = 0) {
        require(relaxationLevel in 0..4) { "緩和段階が不正です" }
        val context = Context(request)
        context.checkRequest()
        require(days.size == 7 && days.map { it.date }.toSet() == request.days.map { it.date }.toSet()) { "提案は対象週の7日分である必要があります" }
        val source = request.days.associateBy { it.date }
        val retainedCounts = request.days.flatMap(context::retained).groupingBy { it.recipeId }.eachCount()
        days.forEach { day ->
            val original = source.getValue(day.date)
            require(day.noMeal == original.noMeal && day.dayLocked == original.dayLocked && day.protectedDay == original.protectedDay) { "提案が要否・固定・保護状態を変更しています" }
            if (context.isProtected(original)) {
                require(day == original) { "保護対象の日の料理を変更できません" }
            } else {
                val fixed = context.retained(original)
                require(fixed.all { it in day.items }) { "固定料理を変更できません" }
                require(day.items.filter { it.locked }.toSet() == fixed.filter { it.locked }.toSet()) { "提案で固定状態を変更できません" }
                require(if (day.noMeal) day.items.isEmpty() else complete(day.items)) { "提案の献立構成が不完全です" }
                day.items.filter { it !in fixed }.forEach { item ->
                    val recipe = context.recipes[item.recipeId] ?: error("レシピ参照が切れています")
                    require(recipe.recipe.proposalPriority != "retired" && recipe.recipe.category == item.role) { "新規候補のカテゴリまたは提案優先度が不正です" }
                    require(relaxationLevel == 4 || !context.recent(item.recipeId, day.date)) { "2週間以内の料理が含まれています" }
                }
            }
            require(day.items.map { it.recipeId }.distinct().size == day.items.size) { "同じ日の料理が重複しています" }
            day.items.forEach { require(it.recipeId in context.recipes) { "レシピ参照が切れています" } }
        }
        if (relaxationLevel < 4) {
            days.flatMap { it.items }.groupingBy { it.recipeId }.eachCount().forEach { (id, count) ->
                require(count <= maxOf(1, retainedCounts[id] ?: 0)) { "同じ週の料理が重複しています" }
            }
        }
    }

    fun evaluate(request: ProposalRequestV2, days: List<ProposalDayV2>, relaxationLevel: Int = 0): ProposalEvaluationV2 =
        Context(request).evaluate(days, relaxationLevel)

    private data class PatternDay(val day: ProposalDayV2, val fixed: List<ProposalItemV2>, val roles: List<String>)
    private data class Slot(val dayIndex: Int, val role: String)
    private data class Ranked(val days: List<ProposalDayV2>, val evaluation: ProposalEvaluationV2, val tie: Long, val level: Int)

    private class Context(val request: ProposalRequestV2) {
        val recipes = request.recipes.associateBy { it.recipe.id }
        val active = request.recipes.filter { it.recipe.proposalPriority != "retired" }.sortedBy { it.recipe.id }
        private val activeByRole = active.groupBy { it.recipe.category }
        private val foods = request.foods.associateBy { it.master.id }
        private val history = request.history.groupBy({ it.second }, { it.first })
        private val otherPlans = request.otherPlans.filter { it.first !in request.weekStart..request.weekStart.plusDays(6) }.groupBy({ it.second }, { it.first })
        private val orderedDays = request.days.sortedBy { it.date }
        private val allFixedIds = orderedDays.flatMap(::retained).map { it.recipeId }.toSet()
        private val groups = recipes.mapValues { (_, value) -> value.recipe.nutritionGroups.split(',').filter(String::isNotBlank).toSet() }
        private val foodsByName = buildMap {
            request.foods.forEach { food ->
                put(food.master.normalizedName, food)
                food.aliases.forEach { put(it.normalizedAlias, food) }
                food.builtInAliases.forEach { put(it.normalizedAlias, food) }
            }
        }
        private val majors = recipes.mapValues { (_, details) -> details.ingredients.mapNotNull { ingredient ->
            val food = ingredient.foodMasterId?.let(foods::get) ?: foodsByName[ingredient.normalizedName]
            when {
                food?.master?.isMainIngredient == true -> if (ingredient.foodMasterId == null) "name:${ingredient.normalizedName}" else "food:${food.master.id}"
                else -> null
            }
        }.toSet() }
        private val seasonal = recipes.mapValues { (_, details) -> details.ingredients.mapNotNull { it.foodMasterId }.toSet()
            .mapNotNull(foods::get).filter { it.master.isMainIngredient } }
        private val recentByDate = mutableMapOf<LocalDate, Set<String>>()
        private val rankedCandidates = mutableMapOf<Pair<String, Int>, List<RecipeDetailsV2>>()

        fun isProtected(day: ProposalDayV2) = day.protectedDay || day.dayLocked || day.date < request.today
        fun retained(day: ProposalDayV2): List<ProposalItemV2> = if (isProtected(day)) day.items else day.items.filter { it.locked }
        fun recent(id: String, date: LocalDate): Boolean = id in recentByDate.getOrPut(date) {
            recipes.keys.filterTo(mutableSetOf()) { recipeId ->
                history[recipeId].orEmpty().any { ChronoUnit.DAYS.between(it, date) in 0L..13L } ||
                    otherPlans[recipeId].orEmpty().any { abs(ChronoUnit.DAYS.between(it, date)) <= 13L }
            }
        }

        fun checkRequest() {
            require(request.weekStart.dayOfWeek.value == 1) { "週開始日は月曜日である必要があります" }
            val currentWeek = request.today.minusDays(request.today.dayOfWeek.value - 1L)
            require(request.weekStart >= currentWeek) { "過去週の自動提案はできません" }
            require(request.days.size == 7 && request.days.map { it.date }.toSet() == (0L..6L).map { request.weekStart.plusDays(it) }.toSet()) { "対象週の7日分を指定してください" }
            require(recipes.size == request.recipes.size) { "レシピIDが重複しています" }
            require(foods.size == request.foods.size) { "材料IDが重複しています" }
            request.recipes.forEach { details ->
                require(details.recipe.category in roles && details.recipe.effortLevel in efforts && details.recipe.proposalPriority in priorities) { "レシピのカテゴリ・手間・優先度が不正です" }
            }
            request.days.forEach { day ->
                require(!day.noMeal || day.items.isEmpty()) { "不要日には料理を設定できません" }
                require(!day.dayLocked || !day.noMeal && complete(day.items)) { "不要日・未設定日は固定できません" }
                require(day.items.map { it.recipeId }.distinct().size == day.items.size && day.items.map { it.role }.distinct().size == day.items.size) { "同日料理または役割が重複しています" }
                day.items.forEach { item ->
                    require(item.recipeId in recipes && item.role in roles) { "料理の参照または役割が不正です" }
                    if (day.dayLocked || item.locked) require(recipes.getValue(item.recipeId).recipe.category == item.role) { "固定料理のカテゴリが役割と一致しません" }
                }
                if (isProtected(day)) require(day.items.isEmpty() || complete(day.items)) { "保存済みの献立構成が不完全です" }
                val fixedRoles = retained(day).map { it.role }.toSet()
                require("ONE_DISH" !in fixedRoles || fixedRoles.size == 1) { "一品ものと3品構成を同時に固定できません" }
            }
        }

        fun patterns(): List<List<PatternDay>> {
            var result = listOf(emptyList<PatternDay>())
            orderedDays.forEach { day ->
                val fixed = retained(day)
                val options = when {
                    isProtected(day) || day.noMeal -> listOf(emptyList())
                    fixed.any { it.role == "ONE_DISH" } -> listOf(emptyList())
                    fixed.isNotEmpty() -> listOf(listOf("MAIN", "SIDE", "SOUP").filter { role -> fixed.none { it.role == role } })
                    else -> listOf(listOf("ONE_DISH"), listOf("MAIN", "SIDE", "SOUP"))
                }
                result = result.flatMap { prefix -> options.map { prefix + PatternDay(day, fixed, it) } }
            }
            return result
        }

        fun witness(pattern: List<PatternDay>, relaxed: Boolean): List<ProposalDayV2>? {
            val slots = pattern.flatMapIndexed { index, day -> day.roles.map { Slot(index, it) } }
            if (slots.isEmpty()) return pattern.map { it.day.copy(items = it.fixed) }
            val possible = slots.map { slot ->
                val day = pattern[slot.dayIndex]
                val fixedIds = day.fixed.map { it.recipeId }.toSet()
                activeByRole[slot.role].orEmpty().filter { recipe ->
                    val id = recipe.recipe.id
                    id !in fixedIds && (relaxed || id !in allFixedIds && !recent(id, day.day.date))
                }
            }
            if (possible.any { it.isEmpty() }) return null
            val selected = if (relaxed) {
                possible.map { choices -> choices.minWith(compareBy<RecipeDetailsV2>({ priority(it.recipe.proposalPriority, slots.size) }, { it.recipe.id })) }
            } else {
                val candidates = possible.flatten().distinctBy { it.recipe.id }.sortedBy { it.recipe.id }
                if (candidates.size < slots.size) return null
                val allowed = possible.map { list -> list.map { it.recipe.id }.toSet() }
                val costs = Array(slots.size) { row -> LongArray(candidates.size) { column ->
                    val recipe = candidates[column].recipe
                    if (recipe.id in allowed[row]) priority(recipe.proposalPriority, slots.size).toLong() else infinity
                } }
                val assignment = minimumAssignment(costs) ?: return null
                assignment.map { candidates[it] }
            }
            val items = pattern.map { it.fixed.toMutableList() }
            slots.forEachIndexed { index, slot -> items[slot.dayIndex] += ProposalItemV2(selected[index].recipe.id, slot.role) }
            return pattern.mapIndexed { index, day -> if (isProtected(day.day)) day.day else day.day.copy(items = items[index].sortedBy { roles.indexOf(it.role) }) }
        }

        fun optimize(witnesses: List<List<ProposalDayV2>>, level: Int): List<ProposalDayV2> {
            var beam = witnesses.map { ranked(it, level) }.sortedWith(ranking).take(beamWidth)
            // These are evaluation candidates only. Each full-catalog matching witness remains viable.
            repeat(2) {
                val expanded = ArrayList<Ranked>()
                expanded.addAll(beam)
                beam.forEach { state ->
                    val used = state.days.flatMap { it.items }.map { it.recipeId }.toSet()
                    state.days.forEachIndexed { index, day ->
                        val dayIds = day.items.map { it.recipeId }.toSet()
                        if (!isProtected(day) && !day.noMeal) day.items.forEachIndexed { itemIndex, item ->
                            if (!item.locked) {
                                val choices = orderedCandidates(item.role, day.date.monthValue).asSequence().filter { recipe ->
                                    val id = recipe.recipe.id
                                    id !in dayIds && (level == 4 || id !in used && !recent(id, day.date))
                                }.take(20)
                                choices.forEach { recipe ->
                                    val replacement = day.items.toMutableList().apply { set(itemIndex, ProposalItemV2(recipe.recipe.id, item.role)) }
                                    val candidate = state.days.toMutableList().apply { set(index, day.copy(items = replacement)) }
                                    expanded += ranked(candidate, level)
                                }
                            }
                        }
                    }
                }
                beam = expanded.distinctBy { signature(it.days) }.sortedWith(ranking).take(beamWidth)
            }
            return beam.first().days
        }

        private fun orderedCandidates(role: String, month: Int) = rankedCandidates.getOrPut(role to month) {
            activeByRole[role].orEmpty().sortedWith(compareBy<RecipeDetailsV2>(
                { priority(it.recipe.proposalPriority, 21) }, { efforts.indexOf(it.recipe.effortLevel) },
                { -seasonCount(it.recipe.id, month) }, { history[it.recipe.id].orEmpty().size },
                { seeded(listOf(it.recipe.id), request.seed) }, { it.recipe.id },
            ))
        }

        private fun ranked(days: List<ProposalDayV2>, level: Int) = Ranked(days, evaluate(days, level), seeded(signature(days), request.seed), level)
        private fun signature(days: List<ProposalDayV2>) = days.flatMap { day -> listOf(day.date.toString()) + day.items.map { "${it.role}:${it.recipeId}" } }
        private val ranking = Comparator<Ranked> { a, b ->
            val ea = a.evaluation; val eb = b.evaluation
            val valuesA = listOf(ea.lowCount, ea.normalCount, ea.effortPenalty, if (a.level >= 3) 0 else ea.nutritionPenalty, if (a.level >= 2) 0 else ea.wastePenalty, if (a.level >= 1) 0 else -ea.seasonalScore, ea.historicalCount)
            val valuesB = listOf(eb.lowCount, eb.normalCount, eb.effortPenalty, if (b.level >= 3) 0 else eb.nutritionPenalty, if (b.level >= 2) 0 else eb.wastePenalty, if (b.level >= 1) 0 else -eb.seasonalScore, eb.historicalCount)
            valuesA.zip(valuesB).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) }
                ?: a.tie.compareTo(b.tie)
        }

        fun evaluate(days: List<ProposalDayV2>, level: Int): ProposalEvaluationV2 {
            val configured = days.sortedBy { it.date }.filter { !it.noMeal && it.items.isNotEmpty() }
            val warnings = mutableListOf<String>()
            val dishes = configured.flatMap { it.items }.mapNotNull { recipes[it.recipeId] }
            val burdens = configured.map { day ->
                val rows = day.items.map { recipes.getValue(it.recipeId).recipe }
                rows.sumOf { efforts.indexOf(it.effortLevel) + 1 }.toDouble() +
                    (rows.sumOf { it.cookingTimeMinutes ?: 0 } / 60.0 * .5).coerceAtMost(2.0)
            }
            val adjacent = configured.zipWithNext().count { (a, b) ->
                a.items.any { recipes.getValue(it.recipeId).recipe.effortLevel == "HARD" } &&
                    b.items.any { recipes.getValue(it.recipeId).recipe.effortLevel == "HARD" }
            }
            val effort = if (burdens.isEmpty()) 0 else ((burdens.max() - burdens.min()) * 100).roundToInt() + adjacent * 1000
            if (adjacent > 0) warnings += "手間が大きい日が連続しています"
            val recent = configured.sumOf { day -> day.items.count { recent(it.recipeId, day.date) } }
            if (recent > 0) warnings += "2週間以内の実績・別週予定と重複する料理が${recent}件含まれます"
            if (dishes.groupingBy { it.recipe.id }.eachCount().any { it.value > 1 }) warnings += "同じ週に重複する料理があります"
            var nutrition = 0
            val missing = IntArray(3)
            configured.forEach { day ->
                val present = day.items.flatMap { groups[it.recipeId].orEmpty() }.toSet()
                val have = listOf(day.items.size == 3 || "GRAIN" in present, present.any { it in proteins }, present.any { it in vegetables })
                have.forEachIndexed { index, included -> if (!included) {
                    nutrition += if (index == 0) 1 else 2
                    missing[index]++
                    warnings += "${day.date}: ${nutritionNames[index]}が不足しています"
                } }
            }
            if (configured.isNotEmpty()) missing.forEachIndexed { index, count -> if (count * 2 >= configured.size) {
                nutrition += 5; warnings += "週全体で${nutritionNames[index]}が不足しています"
            } }
            val foodRecipes = mutableMapOf<String, MutableSet<String>>()
            dishes.forEach { details -> majors[details.recipe.id].orEmpty().forEach { key -> foodRecipes.getOrPut(key) { mutableSetOf() }.add(details.recipe.id) } }
            val waste = foodRecipes.count { it.value.size == 1 } * 10
            val season = configured.flatMap { day -> day.items.flatMap { seasonal[it.recipeId].orEmpty() }
                .filter { day.date.monthValue in it.seasonMonths }.map { it.master.id } }.toSet().size
            if (level > 0) warnings.addAll(0, relaxationWarnings.take(level))
            return ProposalEvaluationV2(warnings.distinct(), dishes.count { it.recipe.proposalPriority == "low" },
                dishes.count { it.recipe.proposalPriority == "normal" }, effort, recent, nutrition, waste, season,
                dishes.sumOf { history[it.recipe.id].orEmpty().size })
        }

        private fun seasonCount(id: String, month: Int) = seasonal[id].orEmpty().count { month in it.seasonMonths }
    }

    private companion object {
        val roles = listOf("ONE_DISH", "MAIN", "SIDE", "SOUP")
        val efforts = listOf("EASY", "NORMAL", "HARD")
        val priorities = setOf("high", "normal", "low", "retired")
        val proteins = setOf("MEAT", "SEAFOOD", "EGG", "SOY")
        val vegetables = setOf("VEGETABLE", "MUSHROOM", "SEAWEED")
        val nutritionNames = listOf("主食", "たんぱく質", "野菜")
        val relaxationWarnings = listOf("旬の優先を解除しました", "食材ロスの優先を解除しました", "栄養目標を警告扱いへ変更しました", "2週間以内・同一週の重複回避を警告扱いへ変更しました")
        const val infinity = 1_000_000_000L
        const val beamWidth = 50
        fun priority(value: String, slots: Int) = when (value) { "low" -> slots + 1; "normal" -> 1; else -> 0 }
        fun complete(items: List<ProposalItemV2>): Boolean {
            val set = items.map { it.role }.toSet()
            return items.size == set.size && (set == setOf("ONE_DISH") || set == setOf("MAIN", "SIDE", "SOUP"))
        }
        fun seeded(ids: List<String>, seed: Long): Long = ids.fold(seed) { value, id -> value * 6364136223846793005L + id.hashCode() }.ushr(1)

        /** Rectangular Hungarian assignment. Forbidden edges never become an insufficiency shortcut. */
        fun minimumAssignment(costs: Array<LongArray>): IntArray? {
            val n = costs.size; val m = costs.first().size
            if (n > m) return null
            val u = LongArray(n + 1); val v = LongArray(m + 1)
            val p = IntArray(m + 1); val way = IntArray(m + 1)
            for (i in 1..n) {
                p[0] = i
                var column = 0
                val min = LongArray(m + 1) { infinity }
                val used = BooleanArray(m + 1)
                do {
                    used[column] = true
                    val row = p[column]
                    var delta = infinity
                    var next = 0
                    for (j in 1..m) if (!used[j]) {
                        val cost = costs[row - 1][j - 1] - u[row] - v[j]
                        if (cost < min[j]) { min[j] = cost; way[j] = column }
                        if (min[j] < delta) { delta = min[j]; next = j }
                    }
                    if (delta >= infinity / 2) return null
                    for (j in 0..m) if (used[j]) { u[p[j]] += delta; v[j] -= delta } else min[j] -= delta
                    column = next
                } while (p[column] != 0)
                do { val previous = way[column]; p[column] = p[previous]; column = previous } while (column != 0)
            }
            val result = IntArray(n)
            for (j in 1..m) if (p[j] != 0) result[p[j] - 1] = j - 1
            return result.takeIf { it.indices.all { row -> costs[row][it[row]] < infinity / 2 } }
        }
    }
}
