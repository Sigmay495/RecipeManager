package jp.local.recipemanager.data.local.v2

import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.domain.model.EffortLevel
import jp.local.recipemanager.domain.model.FoodGroup
import jp.local.recipemanager.domain.model.MenuRole
import jp.local.recipemanager.domain.model.RecipeCategory
import jp.local.recipemanager.domain.model.RecipeStatus
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Validates the version-1 snapshot before a migration changes any table. */
internal object LegacyDatabaseValidator {
    fun validate(tables: Map<String, List<Map<String, Any?>>>) {
        fun rows(table: String) = tables.getValue(table)
        fun index(table: String): Map<String, Map<String, Any?>> {
            val all = rows(table)
            unique(all, "$table のID") { listOf(it.text("id")) }
            return all.associateBy { it.text("id") }
        }
        val recipes = index("recipes")
        val foods = index("foodMasters")
        val weeks = index("weeklyMenus")
        val days = index("menuDays")
        index("menuItems")
        val histories = index("cookingHistories")
        index("historyItems")
        val lists = index("shoppingLists")
        index("recipeIngredients")
        index("shoppingItems")

        recipes.values.forEach { row ->
            normalized(row)
            member(row, "category", RecipeCategory.entries.map { it.name })
            member(row, "effortLevel", EffortLevel.entries.map { it.name })
            member(row, "status", RecipeStatus.entries.map { it.name })
            row["cookingTimeMinutes"]?.let {
                require(row.integer("cookingTimeMinutes") in 1L..1440L) { "調理時間が範囲外です" }
            }
            listOf("lastCookedAt", "initialLastCookedAt").forEach { key ->
                if (row[key] != null) row.date(key)
            }
            row.text("nutritionGroups").takeIf { it.isNotBlank() }?.split(',')?.forEach {
                require(it in FoodGroup.entries.map { group -> group.name }) { "食品群が不正です: $it" }
            }
            timestamps(row, "createdAt", "updatedAt")
            if (row["deletedAt"] != null) timestamps(row, "deletedAt")
        }

        // A built-in alias may repeat its own standard name. User-added aliases may not.
        val names = mutableMapOf<String, String>()
        foods.values.forEach { row ->
            normalized(row)
            row.bool("userEditable")
            row.bool("isMainIngredient")
            val old = names.put(row.text("normalizedName"), row.text("id"))
            require(old == null) { "材料の標準名が競合しています: ${row.text("name")}" }
        }
        unique(rows("foodMasterAliases"), "材料別名") { listOf(it.text("normalizedAlias")) }
        rows("foodMasterAliases").forEach { row ->
            val food = foods[row.text("foodMasterId")] ?: error("別名の材料参照が切れています")
            val key = row.text("normalizedAlias")
            require(key.isNotBlank() && key == TextNormalizer.normalize(row.text("alias"))) { "別名の正規化値が不正です" }
            val existing = names[key]
            require(existing == null || (!food.bool("userEditable") && existing == food.text("id"))) {
                "材料の標準名・別名が競合しています: ${row.text("alias")}"
            }
            names[key] = food.text("id")
        }
        rows("foodMasterGroups").forEach { row ->
            require(row.text("foodMasterId") in foods) { "食品群の材料参照が切れています" }
            member(row, "foodGroup", FoodGroup.entries.map { it.name })
        }
        rows("foodMasterSeasons").forEach { row ->
            require(row.text("foodMasterId") in foods) { "旬の材料参照が切れています" }
            require(row.integer("month") in 1L..12L) { "旬の月が範囲外です" }
        }
        unique(rows("foodMasterGroups"), "材料食品群") { listOf(it.text("foodMasterId"), it.text("foodGroup")) }
        unique(rows("foodMasterSeasons"), "材料の旬") { listOf(it.text("foodMasterId"), it.integer("month").toString()) }

        val ingredients = rows("recipeIngredients")
        unique(ingredients, "レシピ材料順") { listOf(it.text("recipeId"), it.integer("displayOrder").toString()) }
        ingredients.forEach { row ->
            require(row.text("recipeId") in recipes) { "レシピ材料のレシピ参照が切れています" }
            require(row["foodMasterId"] == null || row.text("foodMasterId") in foods) { "レシピ材料の材料参照が切れています" }
            require(row.integer("displayOrder") >= 0L) { "レシピ材料順が負数です" }
            normalized(row)
            amount(row)
        }
        val recipesWithIngredients = ingredients.map { it.text("recipeId") }.toSet()
        require(recipes.keys.all { it in recipesWithIngredients }) { "材料がないレシピがあります" }

        unique(weeks.values.toList(), "週開始日") { listOf(it.text("weekStart")) }
        weeks.values.forEach { row -> monday(row); timestamps(row, "createdAt", "updatedAt") }
        unique(days.values.toList(), "献立日付") { listOf(it.text("date")) }
        days.values.forEach { row ->
            val week = weeks[row.text("weeklyMenuId")] ?: error("献立日の週参照が切れています")
            val start = week.date("weekStart")
            require(row.date("date") in start..start.plusDays(6)) { "献立日が所属週の範囲外です" }
            row.bool("noMeal")
        }
        val items = rows("menuItems")
        items.forEach { row ->
            require(row.text("menuDayId") in days && row.text("recipeId") in recipes) { "献立料理の参照が切れています" }
            member(row, "role", MenuRole.entries.map { it.name })
        }
        unique(items, "同日レシピ") { listOf(it.text("menuDayId"), it.text("recipeId")) }
        unique(items, "献立役割") { listOf(it.text("menuDayId"), it.text("role")) }
        val byDay = items.groupBy { it.text("menuDayId") }
        days.values.forEach { row ->
            val dayItems = byDay[row.text("id")].orEmpty()
            val roles = dayItems.map { it.text("role") }.toSet()
            val complete = roles == setOf("ONE_DISH") || roles == setOf("MAIN", "SIDE", "SOUP")
            require(if (row.bool("noMeal")) dayItems.isEmpty() else complete) { "旧献立の構成が不完全または不要日と矛盾しています" }
        }

        unique(histories.values.toList(), "調理日") { listOf(it.text("cookedDate")) }
        histories.values.forEach { row ->
            val date = row.date("cookedDate")
            val id = row.text("id")
            if (id.startsWith("history-")) require(id == "history-$date") { "旧履歴IDと調理日が一致しません" }
            timestamps(row, "createdAt", "updatedAt")
        }
        val historyItems = rows("historyItems")
        unique(historyItems, "同日同レシピ実績") { listOf(it.text("historyId"), it.text("recipeId")) }
        historyItems.forEach { row ->
            require(row.text("historyId") in histories && row.text("recipeId") in recipes) { "調理実績の参照が切れています" }
            member(row, "role", MenuRole.entries.map { it.name })
        }

        unique(lists.values.toList(), "買い物週") { listOf(it.text("weekStart")) }
        lists.values.forEach { row -> monday(row); timestamps(row, "createdAt", "sourceMenuUpdatedAt") }
        val shopping = rows("shoppingItems")
        unique(shopping, "買い物集約キー") { listOf(it.text("shoppingListId"), it.text("normalizedName"), it.text("unit").trim()) }
        shopping.forEach { row ->
            require(row.text("shoppingListId") in lists) { "買い物項目のリスト参照が切れています" }
            normalized(row)
            amount(row)
            row.bool("checked")
            row.bool("excluded")
        }
        rows("holidays").forEach { row ->
            require(row.date("date").year.toLong() == row.integer("year")) { "祝日の日付と年が一致しません" }
        }
    }

    private fun Map<String, Any?>.text(key: String): String = this[key] as? String ?: error("$key が文字列ではありません")
    private fun Map<String, Any?>.integer(key: String): Long = when (val value = this[key]) {
        is Long -> value
        is Int -> value.toLong()
        else -> error("$key が整数ではありません")
    }
    private fun Map<String, Any?>.bool(key: String): Boolean {
        val value = integer(key)
        require(value == 0L || value == 1L) { "$key が真偽値ではありません" }
        return value == 1L
    }
    private fun Map<String, Any?>.date(key: String): LocalDate {
        val value = text(key)
        val date = LocalDate.parse(value)
        require(value == date.toString()) { "$key が正規の日付ではありません" }
        return date
    }
    private fun normalized(row: Map<String, Any?>) {
        val normalized = row.text("normalizedName")
        require(normalized.isNotBlank() && normalized == TextNormalizer.normalize(row.text("name"))) { "名前の正規化値が不正です" }
    }
    private fun amount(row: Map<String, Any?>) {
        if (row["amount"] == null) return
        require(BigDecimal(row.text("amount")) > BigDecimal.ZERO) { "分量は正数または不明である必要があります" }
    }
    private fun member(row: Map<String, Any?>, key: String, allowed: List<String>) {
        require(row.text(key) in allowed) { "$key に不明な値があります: ${row[key]}" }
    }
    private fun timestamps(row: Map<String, Any?>, vararg keys: String) {
        keys.forEach { key -> Instant.parse(row.text(key)) }
    }
    private fun monday(row: Map<String, Any?>) {
        require(row.date("weekStart").dayOfWeek == DayOfWeek.MONDAY) { "週開始日は月曜日である必要があります" }
    }
    private fun unique(rows: List<Map<String, Any?>>, description: String, key: (Map<String, Any?>) -> List<String>) {
        val seen = HashSet<List<String>>()
        require(rows.all { seen.add(key(it)) }) { "$description が重複しています" }
    }
}
