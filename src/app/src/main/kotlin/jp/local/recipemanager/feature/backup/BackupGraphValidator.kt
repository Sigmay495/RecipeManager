package jp.local.recipemanager.feature.backup

import com.fasterxml.jackson.databind.JsonNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import java.time.DayOfWeek
import java.time.LocalDate

/** Cross-table checks not expressible by the JSON Schema. Called only after structure validation. */
internal class BackupGraphValidator(private val presets: List<BackupPreset>) {
    fun validate(root: JsonNode, legacy: Boolean = false): Set<BackupDraftReference> {
        val data = root.path("data")
        fun index(table: String): Map<String, JsonNode> {
            val rows = data.path(table).toList()
            unique(rows, table) { it.path("id").asText() }
            return rows.associateBy { it.path("id").asText() }
        }
        val recipes = index("recipes")
        val foods = index("userFoodMasters")
        val menus = index("weeklyMenus")
        val days = index("menuDays")
        val items = index("menuItems")
        val histories = index("cookingHistories")
        val historyItems = index("historyItems")
        val lists = index("shoppingLists")
        index("recipeIngredients")
        index("shoppingItems")
        val presetIds = presets.map { it.id }.toSet()
        require(foods.keys.none { it in presetIds }) { "利用者材料IDが内蔵材料と競合しています" }
        val foodIds = foods.keys + presetIds

        val foodNames = mutableMapOf<String, String>()
        fun addName(name: String, foodId: String, alias: Boolean = false) {
            val normalized = TextNormalizer.normalize(name)
            require(normalized.isNotBlank()) { "材料名または別名が空です" }
            val existing = foodNames[normalized]
            require(existing == null || (!alias && existing == foodId)) { "材料名・別名が競合しています: $name" }
            foodNames[normalized] = foodId
        }
        presets.forEach { preset ->
            addName(preset.name, preset.id)
            preset.aliases.forEach { addName(it, preset.id) }
        }
        foods.values.forEach { food ->
            checkNormalized(food)
            addName(food.path("name").asText(), food.path("id").asText())
        }
        recipes.values.forEach(::checkNormalized)
        if (legacy) {
            foods.values.forEach { food ->
                food.path("aliases").forEach { addName(it.asText(), food.path("id").asText(), alias = true) }
            }
        } else {
            val aliases = index("userFoodAliases")
            unique(aliases.values.toList(), "利用者別名") { it.path("normalizedAlias").asText() }
            aliases.values.forEach { alias ->
                val foodId = alias.path("foodMasterId").asText()
                require(foodId in foodIds) { "利用者別名の材料参照が切れています" }
                require(alias.path("normalizedAlias").asText() == TextNormalizer.normalize(alias.path("alias").asText())) { "別名の正規化値が不正です" }
                addName(alias.path("alias").asText(), foodId, alias = true)
            }
        }
        fun foodReference(node: JsonNode) {
            val ref = node.path("foodMasterId")
            require(ref.isNull || ref.asText() in foodIds) { "材料参照が切れています: ${ref.asText()}" }
        }
        val ingredients = data.path("recipeIngredients").toList()
        unique(ingredients, "レシピ材料順") { "${it.path("recipeId")}:${it.path("order")}" }
        ingredients.forEach {
            require(it.path("order").canConvertToInt()) { "材料順序が保存可能な整数範囲を超えています" }
            require(it.path("recipeId").asText() in recipes) { "材料から存在しないレシピへの参照があります" }
            checkNormalized(it)
            foodReference(it)
        }
        val recipesWithIngredients = ingredients.map { it.path("recipeId").asText() }.toSet()
        require(recipes.keys.all(recipesWithIngredients::contains)) { "材料がないレシピがあります" }

        unique(menus.values.toList(), "週開始日") { it.path("weekStart").asText() }
        menus.values.forEach { require(monday(it.path("weekStart").asText())) { "週開始日は月曜日である必要があります" } }
        unique(days.values.toList(), "献立日付") { it.path("date").asText() }
        days.values.forEach { day ->
            val week = menus[day.path("weeklyMenuId").asText()] ?: error("献立日の週参照が切れています")
            val start = LocalDate.parse(week.path("weekStart").asText())
            val date = LocalDate.parse(day.path("date").asText())
            require(date >= start && date <= start.plusDays(6)) { "献立日が所属週の範囲外です" }
        }
        items.values.forEach { item ->
            require(item.path("menuDayId").asText() in days && item.path("recipeId").asText() in recipes) { "献立料理の参照が切れています" }
        }
        val itemsByDay = items.values.groupBy { it.path("menuDayId").asText() }
        days.values.forEach { day ->
            val dishes = itemsByDay[day.path("id").asText()].orEmpty()
            unique(dishes, "同日Recipe") { it.path("recipeId").asText() }
            unique(dishes, "献立役割") { it.path("role").asText() }
            val roles = dishes.map { it.path("role").asText() }.toSet()
            val complete = roles == setOf("one_dish") || roles == setOf("main", "side", "soup")
            require(if (day.path("noMeal").asBoolean()) dishes.isEmpty() else complete || (!legacy && dishes.isEmpty())) { "献立構成が不完全または不要日と矛盾しています" }
            if (!legacy) {
                val locked = day.path("dayLocked").asBoolean()
                require(!locked || complete) { "未設定・不要の日は固定できません" }
                dishes.forEach { dish ->
                    if (locked || dish.path("locked").asBoolean()) {
                        require(recipes.getValue(dish.path("recipeId").asText()).path("category").asText() == dish.path("role").asText()) { "固定料理のカテゴリが役割と一致しません" }
                    }
                }
            }
        }
        unique(histories.values.toList(), "調理日") { it.path("cookedDate").asText() }
        histories.values.forEach { history ->
            val id = history.path("id").asText()
            if (id.startsWith("history-")) {
                val date = LocalDate.parse(id.removePrefix("history-"))
                require(date.toString() == history.path("cookedDate").asText()) { "旧履歴IDと調理日が一致しません" }
            }
        }
        unique(historyItems.values.toList(), "同日同Recipe実績") { "${it.path("historyId")}:${it.path("recipeId")}" }
        historyItems.values.forEach {
            require(it.path("historyId").asText() in histories && it.path("recipeId").asText() in recipes) { "調理履歴の参照が切れています" }
        }
        unique(lists.values.toList(), "買い物週") { it.path("weekStart").asText() }
        lists.values.forEach { require(monday(it.path("weekStart").asText())) { "買い物週の開始日は月曜日である必要があります" } }
        val shopping = data.path("shoppingItems").toList()
        unique(shopping, "買い物集約キー") {
            val key = if (legacy) aggregationKey(null, it.path("normalizedName").asText(), it.path("unit").asText()) else it.path("aggregationKey").asText()
            "${it.path("shoppingListId")}:$key"
        }
        shopping.forEach { item ->
            require(item.path("shoppingListId").asText() in lists) { "買い物リスト参照が切れています" }
            checkNormalized(item)
            if (!legacy) {
                foodReference(item)
                require(item.path("aggregationKey").asText() == aggregationKey(item.path("foodMasterId").takeUnless(JsonNode::isNull)?.asText(), item.path("normalizedName").asText(), item.path("unit").asText())) { "買い物集約キーが材料・単位と一致しません" }
                item.path("recipeUsages").forEach { usage ->
                    require(usage.path("recipeId").isNull || usage.path("recipeId").asText() in recipes) { "買い物の利用料理参照が切れています" }
                }
            }
        }
        if (legacy) return emptySet()

        val outcomes = index("planOutcomes")
        val postponements = index("postponedPlans")
        unique(outcomes.values.toList(), "予定結果") { it.path("menuItemId").asText() }
        unique(postponements.values.toList(), "延期元") { it.path("sourceMenuItemId").asText() }
        val outcomeByItem = outcomes.values.associateBy { it.path("menuItemId").asText() }
        outcomes.values.forEach { outcome ->
            val item = items[outcome.path("menuItemId").asText()] ?: error("予定結果の料理参照が切れています")
            if (outcome.path("state").asText() in setOf("cooked", "changed")) {
                val actual = historyItems[outcome.path("historyItemId").asText()] ?: error("予定結果の履歴参照が切れています")
                val sourceDay = days.getValue(item.path("menuDayId").asText()).path("date").asText()
                require(histories.getValue(actual.path("historyId").asText()).path("cookedDate").asText() == sourceDay) { "予定結果と調理日が一致しません" }
                if (outcome.path("state").asText() == "cooked") require(actual.path("recipeId") == item.path("recipeId")) { "作った結果と実際の料理が一致しません" }
            }
            if (outcome.path("state").asText() == "postponed") {
                require(postponements.values.any { it.path("sourceMenuItemId") == outcome.path("menuItemId") }) { "延期結果に対応する延期予定がありません" }
            }
        }
        postponements.values.forEach { postponed ->
            val sourceId = postponed.path("sourceMenuItemId").asText()
            val source = items[sourceId] ?: error("延期元の料理参照が切れています")
            require(source.path("recipeId") == postponed.path("recipeId")) { "延期元と延期料理が一致しません" }
            require(outcomeByItem[sourceId]?.path("state")?.asText() == "postponed") { "延期元の結果が延期ではありません" }
            if (postponed.path("state").asText() == "confirmed") {
                val target = items[postponed.path("targetMenuItemId").asText()] ?: error("確定延期先の参照が切れています")
                require(target.path("id").asText() != sourceId && target.path("recipeId") == postponed.path("recipeId")) { "延期先の料理が不正です" }
                require(days.getValue(target.path("menuDayId").asText()).path("date") == postponed.path("targetDate")) { "延期先の日付が一致しません" }
            }
        }
        return validateDrafts(index("drafts"), recipes, foodIds, menus, days)
    }

    private fun validateDrafts(
        drafts: Map<String, JsonNode>, recipes: Map<String, JsonNode>, foodIds: Set<String>,
        menus: Map<String, JsonNode>, days: Map<String, JsonNode>,
    ): Set<BackupDraftReference> {
        unique(drafts.values.toList(), "下書きキー") { "${it.path("kind")}:${it.path("targetKey")}" }
        val refs = mutableSetOf<BackupDraftReference>()
        drafts.values.forEach { draft ->
            val id = draft.path("id").asText()
            val payload = draft.path("payload")
            val target = draft.path("targetKey").asText()
            fun add(type: String, entityId: String) {
                require(if (type == "recipe") entityId in recipes else entityId in foodIds) { "下書きの$type 参照が切れています: $entityId" }
                refs += BackupDraftReference(id, type, entityId)
            }
            val candidates = payload.path("foodCandidates").toList()
            unique(candidates, "下書き材料候補") { it.path("localKey").asText() }
            val candidateKeys = candidates.map { it.path("localKey").asText() }.toSet()
            fun form(form: JsonNode) {
                val ingredients = form.path("ingredients").toList()
                unique(ingredients, "下書き材料行") { it.path("localKey").asText() }
                ingredients.forEach { ingredient ->
                    if (!ingredient.path("foodMasterId").isNull) add("food", ingredient.path("foodMasterId").asText())
                    val candidate = ingredient.path("foodCandidateKey")
                    require(candidate.isNull || candidate.asText() in candidateKeys) { "下書きの材料候補参照が切れています" }
                }
            }
            when (draft.path("kind").asText()) {
                "recipe" -> {
                    if (target != "new") add("recipe", target)
                    form(payload.path("form"))
                }
                "import" -> {
                    val entries = payload.path("entries").toList()
                    unique(entries, "Import下書き行") { it.path("localKey").asText() }
                    entries.forEach { entry ->
                        form(entry.path("form"))
                        entry.path("duplicateRecipeIds").forEach { add("recipe", it.asText()) }
                    }
                }
                "menu" -> {
                    require(monday(target)) { "提案下書きの対象週が不正です" }
                    require(payload.path("seed").asText().toLongOrNull() != null) { "提案下書きの乱数シードが64bitの範囲外です" }
                    val draftDays = payload.path("days").toList()
                    val expected = (0L..6L).map { LocalDate.parse(target).plusDays(it).toString() }.toSet()
                    require(draftDays.map { it.path("date").asText() }.toSet() == expected) { "提案下書きの7日が対象週と一致しません" }
                    draftDays.forEach { day ->
                        val dishes = day.path("items").toList()
                        unique(dishes, "提案下書き料理キー") { it.path("localKey").asText() }
                        unique(dishes, "提案下書き役割") { it.path("role").asText() }
                        dishes.forEach { add("recipe", it.path("recipeId").asText()) }
                    }
                }
            }
            val baselines = draft.path("baseRevisions").toList()
            unique(baselines, "下書き基準情報") { "${it.path("entityType")}:${it.path("entityId")}" }
            baselines.forEach { base ->
                val entityId = base.path("entityId").asText()
                val exists = when (base.path("entityType").asText()) {
                    "recipe" -> entityId in recipes
                    "food" -> entityId in foodIds
                    "menu" -> entityId in menus
                    "menuDay" -> entityId in days
                    else -> false
                }
                // Outdated revisions are preserved; non-existent live baseline references are not.
                require(exists) { "下書き基準情報の参照が切れています" }
            }
            // Historical conflicts deliberately do not become live references.
        }
        return refs
    }

    private fun checkNormalized(row: JsonNode) {
        val expected = TextNormalizer.normalize(row.path("name").asText())
        require(expected.isNotBlank() && row.path("normalizedName").asText() == expected) { "名称の正規化値が不正です" }
    }

    private fun monday(value: String) = LocalDate.parse(value).dayOfWeek == DayOfWeek.MONDAY

    private fun unique(rows: List<JsonNode>, label: String, key: (JsonNode) -> String) {
        require(rows.map(key).toSet().size == rows.size) { "$label が重複しています" }
    }

    companion object {
        fun aggregationKey(foodId: String?, normalizedName: String, unit: String): String =
            JsonInput.mapper().createArrayNode().add(if (foodId == null) "name" else "food")
                .add(foodId ?: normalizedName).add(unit.trim()).toString()
    }
}
