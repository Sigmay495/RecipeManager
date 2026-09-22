package jp.local.recipemanager.feature.recipe

import java.math.BigDecimal
import java.net.URI
import java.time.LocalDate

object RecipeValidator {
    fun validate(draft: RecipeDraft, today: LocalDate = LocalDate.now()): Map<String, String> = buildMap {
        val name = draft.name.trim()
        if (name.isEmpty()) put("name", "料理名は必須です")
        else if (name.length > 100) put("name", "料理名は100文字以内です")

        if (draft.ingredients.isEmpty()) put("ingredients", "材料を1件以上入力してください")
        draft.ingredients.forEachIndexed { index, ingredient ->
            if (ingredient.name.trim().isEmpty()) put("ingredient.$index.name", "材料名は必須です")
            if (ingredient.unit.length > 20) put("ingredient.$index.unit", "単位は20文字以内です")
            if (ingredient.amount.isNotBlank()) {
                val amount = ingredient.amount.toBigDecimalOrNull()
                if (amount == null || amount <= BigDecimal.ZERO) put("ingredient.$index.amount", "分量は0より大きい数値です")
            }
        }

        if (draft.cookingTime.isNotBlank()) {
            val minutes = draft.cookingTime.toIntOrNull()
            if (minutes == null || minutes !in 1..1440) put("cookingTime", "調理時間は1～1440分です")
        }
        if (draft.initialLastCookedAt.isNotBlank()) {
            val date = runCatching { LocalDate.parse(draft.initialLastCookedAt) }.getOrNull()
            if (date == null) put("initialLastCookedAt", "日付はYYYY-MM-DD形式です")
            else if (date > today) put("initialLastCookedAt", "未来日は指定できません")
        }
        if (draft.sourceUrl.isNotBlank()) {
            val uri = runCatching { URI(draft.sourceUrl) }.getOrNull()
            if (draft.sourceUrl.length > 2048 || uri?.scheme !in setOf("http", "https") || uri?.host.isNullOrBlank()) {
                put("sourceUrl", "HTTPまたはHTTPSのURLを入力してください")
            }
        }
        if (draft.memo.length > 2000) put("memo", "メモは2000文字以内です")
    }
}
