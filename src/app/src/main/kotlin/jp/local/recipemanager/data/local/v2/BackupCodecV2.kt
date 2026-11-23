package jp.local.recipemanager.data.local.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.feature.backup.BackupFormatResult
import java.time.Instant
import java.util.Locale

/** Maps the v2 storage contract; schema and graph validation remain in BackupFormatAdapter. */
internal class BackupCodecV2 {
    private val mapper = JsonInput.mapper()

    fun document(rows: BackupRowsV2, createdAt: Instant, appVersion: String): ObjectNode {
        val metadata = rows.metadata.associate { it.key to it.value }
        val root = mapper.createObjectNode().apply {
            put("schemaVersion", "2.0"); put("fileType", "full-backup")
            put("createdAt", createdAt.toString()); put("appVersion", appVersion)
            put("masterVersion", metadata["food_master_version"] ?: error("内蔵材料の版情報がありません"))
        }
        val data = root.putObject("data")
        fun <T> table(name: String, values: List<T>, change: (ObjectNode) -> Unit = {}) {
            val array = data.putArray(name)
            values.forEach { value -> array.add(mapper.valueToTree<ObjectNode>(value).also(change)) }
        }
        table("recipes", rows.recipes) {
            it.remove(listOf("lastCookedAt", "nutritionGroups"))
            it.lower("category"); it.lower("effortLevel")
        }
        table("recipeIngredients", rows.ingredients) {
            it.put("order", it.path("displayOrder").asInt()); it.remove("displayOrder")
        }
        table("weeklyMenus", rows.menus)
        table("menuDays", rows.days)
        table("menuItems", rows.menuItems) { it.lower("role") }
        table("cookingHistories", rows.histories)
        table("historyItems", rows.historyItems) { it.lower("role") }
        table("planOutcomes", rows.outcomes)
        table("postponedPlans", rows.postponements)
        table("shoppingLists", rows.shoppingLists) { it.put("updatedAt", it.path("createdAt").asText()) }
        table("shoppingItems", rows.shoppingItems) {
            it.set<JsonNode>("recipeUsages", mapper.readTree(it.path("recipeUsagesJson").asText()))
            it.remove("recipeUsagesJson")
        }
        val groups = rows.groups.groupBy { it.foodMasterId }
        val seasons = rows.seasons.groupBy { it.foodMasterId }
        table("userFoodMasters", rows.foods.filter { it.userEditable }) { food ->
            val id = food.path("id").asText()
            // Jackson's Java bean naming drops the "is" from Kotlin Boolean getters.
            food.put("isMainIngredient", food.path("mainIngredient").asBoolean())
            food.remove("mainIngredient")
            food.putArray("foodGroups").also { a -> groups[id].orEmpty().forEach { a.add(it.foodGroup.lowercase(Locale.ROOT)) } }
            food.putArray("seasonMonths").also { a -> seasons[id].orEmpty().forEach { a.add(it.month) } }
            food.put("createdAt", createdAt.toString()); food.put("updatedAt", createdAt.toString())
        }
        table("userFoodAliases", rows.userAliases)
        table("drafts", rows.drafts) {
            it.set<JsonNode>("payload", mapper.readTree(it.path("payloadJson").asText()))
            it.set<JsonNode>("baseRevisions", mapper.readTree(it.path("baseRevisionsJson").asText()))
            it.remove(listOf("payloadJson", "baseRevisionsJson"))
        }
        val settings = rows.settings.associate { it.key to it.value }
        data.putObject("settings").apply {
            put("backupReminderIntervalDays", settings["backupReminderIntervalDays"]?.toInt() ?: 30)
            text("lastBackupAt", settings["lastBackupAt"]?.takeIf(String::isNotBlank))
            put("holidayDataVersion", metadata["holiday_data_version"] ?: error("祝日の版情報がありません"))
        }
        return root
    }

    fun encode(rows: BackupRowsV2, createdAt: Instant, appVersion: String): ByteArray =
        mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(document(rows, createdAt, appVersion))

    fun decode(ready: BackupFormatResult.Ready, local: BackupRowsV2): BackupRowsV2 {
        val root = ready.document
        val d = root.path("data")
        val foods = d.path("userFoodMasters").map { n ->
            FoodMasterEntityV2(n.t("id"), n.t("name"), n.t("normalizedName"), n.t("baseUnit"), n.b("isMainIngredient"), true, n.l("revision"))
        }
        val groups = d.path("userFoodMasters").flatMap { n -> n.path("foodGroups").map { FoodMasterGroupEntityV2(n.t("id"), it.asText().uppercase(Locale.ROOT)) } }
        val seasons = d.path("userFoodMasters").flatMap { n -> n.path("seasonMonths").map { FoodMasterSeasonEntityV2(n.t("id"), it.asInt()) } }
        val ingredients = d.path("recipeIngredients").map { n ->
            RecipeIngredientEntityV2(n.t("id"), n.t("recipeId"), n.path("order").asInt(), n.t("name"), n.t("normalizedName"), n.nullText("amount"), n.t("unit"), n.t("note"), n.nullText("foodMasterId"))
        }
        val presetIds = local.foods.filterNot { it.userEditable }.map { it.id }.toSet()
        val allGroups = (local.groups.filter { it.foodMasterId in presetIds } + groups).groupBy { it.foodMasterId }
        val nutrition = ingredients.groupBy { it.recipeId }.mapValues { (_, rows) ->
            rows.mapNotNull { it.foodMasterId }.flatMap { allGroups[it].orEmpty().map { g -> g.foodGroup } }.toSortedSet().joinToString(",")
        }
        return local.copy(
            recipes = d.path("recipes").map { n ->
                RecipeEntityV2(n.t("id"), n.t("name"), n.t("normalizedName"), n.t("category").uppercase(Locale.ROOT), n.path("cookingTimeMinutes").takeUnless(JsonNode::isNull)?.asInt(),
                    n.t("effortLevel").uppercase(Locale.ROOT), n.nullText("sourceUrl"), n.t("memo"), null, nutrition[n.t("id")].orEmpty(),
                    n.t("proposalPriority"), n.l("revision"), n.t("createdAt"), n.t("updatedAt"))
            },
            ingredients = ingredients,
            menus = d.path("weeklyMenus").map { n -> WeeklyMenuEntityV2(n.t("id"), n.t("weekStart"), n.t("createdAt"), n.t("updatedAt"), n.l("revision")) },
            days = d.path("menuDays").map { n -> MenuDayEntityV2(n.t("id"), n.t("weeklyMenuId"), n.t("date"), n.b("noMeal"), n.t("noMealReason"), n.t("noMealMemo"), n.t("requirementOrigin"), n.b("dayLocked"), n.l("revision")) },
            menuItems = d.path("menuItems").map { n -> MenuItemEntityV2(n.t("id"), n.t("menuDayId"), n.t("recipeId"), n.t("recipeNameSnapshot"), n.t("role").uppercase(Locale.ROOT), n.b("locked")) },
            histories = d.path("cookingHistories").map { n -> CookingHistoryEntityV2(n.t("id"), n.t("cookedDate"), n.t("createdAt"), n.t("updatedAt")) },
            historyItems = d.path("historyItems").map { n -> HistoryItemEntityV2(n.t("id"), n.t("historyId"), n.t("recipeId"), n.t("recipeNameSnapshot"), n.t("role").uppercase(Locale.ROOT)) },
            outcomes = d.path("planOutcomes").map { n -> PlanOutcomeEntityV2(n.t("id"), n.t("menuItemId"), n.t("state"), n.nullText("historyItemId"), n.t("updatedAt")) },
            postponements = d.path("postponedPlans").map { n -> PostponedPlanEntityV2(n.t("id"), n.t("sourceMenuItemId"), n.t("recipeId"), n.t("targetDate"), n.t("state"), n.nullText("targetMenuItemId"), n.t("updatedAt")) },
            shoppingLists = d.path("shoppingLists").map { n -> ShoppingListEntityV2(n.t("id"), n.t("weekStart"), n.t("sourceMenuUpdatedAt"), n.t("createdAt"), n.nullText("sourceFingerprint"), n.b("needsRegeneration")) },
            shoppingItems = d.path("shoppingItems").map { n -> ShoppingItemEntityV2(n.t("id"), n.t("shoppingListId"), n.t("name"), n.t("normalizedName"), n.nullText("amount"), n.t("unit"), n.path("recipeUsages").toString(), n.b("checked"), n.b("excluded"), n.nullText("foodMasterId"), n.t("aggregationKey"), n.b("hasUnknownAmount")) },
            foods = foods, groups = groups, seasons = seasons,
            userAliases = d.path("userFoodAliases").map { n -> UserFoodAliasEntityV2(n.t("id"), n.t("foodMasterId"), n.t("alias"), n.t("normalizedAlias"), n.t("updatedAt")) },
            drafts = d.path("drafts").map { n -> DraftEntityV2(n.t("id"), n.t("kind"), n.t("targetKey"), n.path("payloadVersion").asInt(), n.path("payload").toString(), n.path("baseRevisions").toString(), n.t("updatedAt")) },
            references = ready.draftReferences.map { DraftReferenceEntityV2(it.draftId, it.entityType, it.entityId) },
            settings = listOf(SettingEntityV2("backupReminderIntervalDays", d.path("settings").t("backupReminderIntervalDays")), SettingEntityV2("lastBackupAt", root.t("createdAt"))),
        )
    }

    private fun ObjectNode.lower(key: String) { put(key, path(key).asText().lowercase(Locale.ROOT)) }
    private fun ObjectNode.text(key: String, value: String?) { if (value == null) putNull(key) else put(key, value) }
    private fun JsonNode.t(key: String) = path(key).asText()
    private fun JsonNode.b(key: String) = path(key).asBoolean()
    private fun JsonNode.l(key: String) = path(key).asLong()
    private fun JsonNode.nullText(key: String) = path(key).takeUnless(JsonNode::isNull)?.asText()
}
