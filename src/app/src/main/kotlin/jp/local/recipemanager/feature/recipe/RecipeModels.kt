package jp.local.recipemanager.feature.recipe

import jp.local.recipemanager.domain.model.EffortLevel
import jp.local.recipemanager.domain.model.RecipeCategory
import jp.local.recipemanager.domain.model.RecipeStatus
import java.time.LocalDate

data class IngredientDraft(
    val name: String = "",
    val amount: String = "",
    val unit: String = "",
    val note: String = "",
)

data class RecipeDraft(
    val id: String? = null,
    val name: String = "",
    val category: RecipeCategory = RecipeCategory.MAIN,
    val ingredients: List<IngredientDraft> = listOf(IngredientDraft()),
    val cookingTime: String = "",
    val effortLevel: EffortLevel = EffortLevel.NORMAL,
    val initialLastCookedAt: String = "",
    val sourceUrl: String = "",
    val memo: String = "",
)

data class RecipeFilters(
    val query: String = "",
    val category: RecipeCategory? = null,
    val maximumMinutes: Int? = null,
    val effortLevel: EffortLevel? = null,
    val status: RecipeStatus = RecipeStatus.ACTIVE,
    val sort: RecipeSort = RecipeSort.NAME,
)

enum class RecipeSort { NAME, UPDATED_AT, LAST_COOKED_AT }

data class RecipeValidation(
    val fieldErrors: Map<String, String> = emptyMap(),
    val duplicateWarning: String? = null,
) {
    val isValid: Boolean get() = fieldErrors.isEmpty()
}

fun RecipeCategory.displayName(): String = when (this) {
    RecipeCategory.ONE_DISH -> "一品もの"
    RecipeCategory.MAIN -> "主菜"
    RecipeCategory.SIDE -> "副菜"
    RecipeCategory.SOUP -> "汁物"
}

fun EffortLevel.displayName(): String = when (this) {
    EffortLevel.EASY -> "簡単"
    EffortLevel.NORMAL -> "普通"
    EffortLevel.HARD -> "大変"
}

fun LocalDate?.displayText(): String = this?.toString() ?: "未設定"
