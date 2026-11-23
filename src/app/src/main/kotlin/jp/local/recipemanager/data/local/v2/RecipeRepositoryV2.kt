package jp.local.recipemanager.data.local.v2

import androidx.room.withTransaction
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.domain.model.EffortLevel
import jp.local.recipemanager.domain.model.RecipeCategory
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class RecipeInputV2(
    val name: String,
    val category: String,
    val ingredients: List<RecipeIngredientInputV2>,
    val id: String? = null,
    val expectedRevision: Long? = null,
    val cookingTimeMinutes: Int? = null,
    val effortLevel: String = "NORMAL",
    val sourceUrl: String? = null,
    val memo: String = "",
    val proposalPriority: String = "normal",
)

data class RecipeIngredientInputV2(
    val name: String,
    val amount: BigDecimal? = null,
    val unit: String = "",
    val note: String = "",
    val foodMasterId: String? = null,
    val newFood: FoodInputV2? = null,
    val id: String? = null,
)

/** Staged v2 API. Reads and writes share transaction snapshots; no v1 application wiring. */
class RecipeRepositoryV2(
    private val database: RecipeManagerDatabaseV2,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val dao get() = database.recipeDao()
    private val foodRepository get() = FoodRepositoryV2(database)
    private val mapper = JsonInput.mapper()

    suspend fun find(id: String): RecipeDetailsV2? = database.withTransaction {
        dao.find(id)?.ordered()
    }

    suspend fun search(
        query: String = "",
        category: String? = null,
        effortLevel: String? = null,
        maxCookingTimeMinutes: Int? = null,
        includeRetired: Boolean = true,
    ): List<RecipeDetailsV2> = database.withTransaction {
        require(category == null || category in RecipeCategory.entries.map { it.name }) { "カテゴリが不正です" }
        require(effortLevel == null || effortLevel in EffortLevel.entries.map { it.name }) { "手間レベルが不正です" }
        require(maxCookingTimeMinutes == null || maxCookingTimeMinutes in 1..1440) { "調理時間は1～1440分です" }
        val normalized = TextNormalizer.normalize(query)
        val matchingFoods = if (normalized.isEmpty()) emptySet() else foodRepository.search(query)
            .map { it.master.id }.toSet()
        dao.all().filter { details ->
            val recipe = details.recipe
            (category == null || recipe.category == category) &&
                (effortLevel == null || recipe.effortLevel == effortLevel) &&
                (includeRetired || recipe.proposalPriority != "retired") &&
                (maxCookingTimeMinutes == null || recipe.cookingTimeMinutes?.let { it <= maxCookingTimeMinutes } == true) &&
                (normalized.isEmpty() || recipe.normalizedName.contains(normalized) || details.ingredients.any {
                    it.normalizedName.contains(normalized) || it.foodMasterId in matchingFoods
                })
        }.map { it.ordered() }
    }

    suspend fun save(input: RecipeInputV2): RecipeEntityV2 = database.withTransaction {
        validateInput(input)
        val old = input.id?.let { dao.find(it) ?: error("レシピが見つかりません") }
        if (old == null) require(input.expectedRevision == null) { "新規レシピに更新元revisionは指定できません" }
        else {
            require(input.expectedRevision == old.recipe.revision) { "レシピが変更されています。再読込してください" }
            if (old.recipe.category != input.category) {
                require(dao.incompatibleFixedReferences(old.recipe.id, input.category) == 0) {
                    "固定料理の役割とカテゴリが一致しません。先に固定解除または献立変更を行ってください"
                }
            }
        }
        val id = old?.recipe?.id ?: UUID.randomUUID().toString()
        val now = Instant.now(clock).toString()
        val ingredientIds = input.ingredients.mapNotNull { it.id }
        require(ingredientIds.distinct().size == ingredientIds.size) { "材料行IDが重複しています" }
        input.ingredients.forEach { ingredient ->
            ingredient.id?.let { ingredientId ->
                require(old != null && dao.ingredient(ingredientId)?.recipeId == id) { "材料行IDがこのレシピに属していません" }
            }
        }
        val oldIngredients = old?.ingredients.orEmpty().sortedBy { it.displayOrder }
        val ingredients = input.ingredients.mapIndexed { order, ingredient ->
            val food = when {
                ingredient.newFood != null -> {
                    require(ingredient.foodMasterId == null) { "既存材料と新規材料は同時指定できません" }
                    require(ingredient.newFood.id == null && ingredient.newFood.expectedRevision == null) { "新規材料には既存IDを指定できません" }
                    foodRepository.save(ingredient.newFood)
                }
                ingredient.foodMasterId != null -> dao.food(ingredient.foodMasterId) ?: error("材料マスタが見つかりません")
                else -> foodRepository.findByExactName(ingredient.name)
            }
            RecipeIngredientEntityV2(
                ingredient.id ?: oldIngredients.getOrNull(order)?.id?.takeUnless { it in ingredientIds }
                    ?: UUID.randomUUID().toString(), id, order,
                ingredient.name.trim(), TextNormalizer.normalize(ingredient.name),
                ingredient.amount?.toPlainString(), ingredient.unit.trim(), ingredient.note.trim(), food?.id,
            )
        }
        val groups = ingredients.mapNotNull { it.foodMasterId }.distinct().flatMap { dao.foodGroups(it) }.toSortedSet()
        val recipe = RecipeEntityV2(
            id, input.name.trim(), TextNormalizer.normalize(input.name), input.category, input.cookingTimeMinutes,
            input.effortLevel, input.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }, input.memo.trim(),
            dao.lastCookedAt(id), groups.joinToString(","), input.proposalPriority,
            old?.recipe?.revision ?: 1L, old?.recipe?.createdAt ?: now, now,
        )
        if (old != null && recipe.copy(updatedAt = old.recipe.updatedAt) == old.recipe && ingredients == oldIngredients) {
            return@withTransaction old.recipe
        }
        val saved = if (old == null) recipe else {
            require(old.recipe.revision < Long.MAX_VALUE) { "revisionの上限に達しました" }
            recipe.copy(revision = old.recipe.revision + 1L)
        }
        if (old == null) dao.insertRecipe(saved)
        else check(dao.updateRecipe(saved) == 1) { "レシピの保存に失敗しました" }
        dao.deleteIngredients(id)
        dao.insertIngredients(ingredients)
        if (old == null || ingredientSource(old.ingredients) != ingredientSource(ingredients)) {
            invalidateShoppingSources(id)
        }
        saved
    }

    suspend fun delete(id: String, expectedRevision: Long, confirmed: Boolean) = database.withTransaction {
        require(confirmed) { "削除の確認が必要です" }
        val recipe = dao.find(id)?.recipe ?: error("レシピが見つかりません")
        require(recipe.revision == expectedRevision) { "レシピが変更されています。再読込してください" }
        require(dao.protectedReferenceCount(id) == 0 && !DraftFoodMergeSupport.referencesRecipe(database, id)) {
            "献立・履歴・延期予定・下書きから参照されるレシピは削除できません"
        }
        // Shopping usages are immutable display snapshots, not deletion-protecting references.
        // Clear only the optional ID; retain names, amounts and purchase states in the same transaction.
        dao.shoppingItems().forEach { item ->
            val usages = mapper.readTree(item.recipeUsagesJson)
            require(usages.isArray) { "買い物の利用料理情報が不正です" }
            var changed = false
            usages.forEach { usage ->
                require(usage.isObject && usage.path("recipeNameSnapshot").isTextual &&
                    (usage.path("recipeId").isNull || usage.path("recipeId").isTextual)) { "買い物の利用料理情報が不正です" }
                if (usage.path("recipeId").asText() == id) {
                    (usage as ObjectNode).putNull("recipeId")
                    changed = true
                }
            }
            if (changed) dao.updateShoppingUsages(item.id, usages.toString())
        }
        dao.deleteIngredients(id)
        check(dao.deleteRecipe(id, expectedRevision) == 1) { "レシピの削除に失敗しました" }
    }

    private suspend fun invalidateShoppingSources(recipeId: String) {
        dao.invalidateMenuShoppingLists(recipeId)
        dao.shoppingItems().forEach { item ->
            val usages = mapper.readTree(item.recipeUsagesJson)
            require(usages.isArray) { "買い物の利用料理情報が不正です" }
            if (usages.any { it.path("recipeId").asText() == recipeId }) dao.invalidateShoppingList(item.shoppingListId)
        }
    }

    private fun ingredientSource(ingredients: List<RecipeIngredientEntityV2>) = ingredients.sortedBy { it.displayOrder }.map {
        listOf(it.name, it.normalizedName, it.amount, it.unit, it.note, it.foodMasterId)
    }

    private fun RecipeDetailsV2.ordered() = copy(ingredients = ingredients.sortedBy { it.displayOrder })

    internal fun validateInput(input: RecipeInputV2) {
        require(input.name.trim().length in 1..100 && TextNormalizer.normalize(input.name).isNotEmpty()) { "料理名は1～100文字です" }
        require(input.category in RecipeCategory.entries.map { it.name }) { "カテゴリが不正です" }
        require(input.effortLevel in EffortLevel.entries.map { it.name }) { "手間レベルが不正です" }
        require(input.proposalPriority in setOf("high", "normal", "low", "retired")) { "提案優先度が不正です" }
        require(input.cookingTimeMinutes == null || input.cookingTimeMinutes in 1..1440) { "調理時間は1～1440分です" }
        require(input.memo.length <= 2000) { "メモは2000文字以内です" }
        require(input.ingredients.isNotEmpty()) { "材料を1件以上入力してください" }
        input.ingredients.forEach {
            require(it.name.trim().length in 1..100 && TextNormalizer.normalize(it.name).isNotEmpty()) { "材料名は1～100文字です" }
            require(it.unit.length <= 20) { "単位は20文字以内です" }
            require(it.note.length <= 200) { "材料の注記は200文字以内です" }
            require(it.amount == null || it.amount > BigDecimal.ZERO) { "分量は0より大きい数値です" }
        }
        val url = input.sourceUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            val uri = runCatching { URI(url) }.getOrNull()
            require(url.length <= 2048 && uri?.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") && !uri?.host.isNullOrBlank()) {
                "HTTPまたはHTTPSのURLを入力してください"
            }
        }
    }
}
