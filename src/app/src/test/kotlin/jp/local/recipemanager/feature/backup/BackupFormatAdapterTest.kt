package jp.local.recipemanager.feature.backup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class BackupFormatAdapterTest {
    private val mapper = JsonInput.mapper()
    private val rootDir = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "document/schemas/backup-2.0.schema.json").isFile }
    private val legacySchema = mapper.readTree(File(rootDir, "document/schemas/backup-1.0.schema.json"))
    private val currentSchema = mapper.readTree(File(rootDir, "document/schemas/backup-2.0.schema.json"))
    private fun adapter(presets: List<BackupPreset> = listOf(BackupPreset("builtin-rice", "米", listOf("ご飯")))) =
        BackupFormatAdapter(legacySchema, currentSchema, presets, "1.0.0")

    @Test fun legacyConversionPreservesIdsAliasesChecksAndDecimalPrecision() {
        val input = legacy()
        val original = input.deepCopy()
        val converted = ready(input).document
        val d = converted.path("data")
        assertEquals(original, input)
        assertEquals("2.0", converted.path("schemaVersion").asText())
        assertEquals(R, d.path("recipes").single().path("id").asText())
        assertEquals("normal", d.path("recipes").single().path("proposalPriority").asText())
        assertFalse(d.path("recipes").single().has("initialLastCookedAt"))
        assertFalse(d.path("recipes").single().has("lastCookedAt"))
        assertEquals("history-2026-09-28", d.path("cookingHistories").single().path("id").asText())
        assertEquals("0.12345678901234567890123456789", d.path("recipeIngredients").single().path("amount").asText())
        assertEquals("Ａ Ｂ", d.path("userFoodAliases").single().path("alias").asText())
        assertEquals("ab", d.path("userFoodAliases").single().path("normalizedAlias").asText())
        assertEquals(F, d.path("userFoodAliases").single().path("foodMasterId").asText())
        assertEquals("", d.path("userFoodMasters").single().path("baseUnit").asText())
        assertEquals(listOf("soy", "vegetable"), d.path("userFoodMasters").single().path("foodGroups").map { it.asText() })
        val shopping = d.path("shoppingItems").single()
        assertTrue(shopping.path("checked").asBoolean())
        assertTrue(shopping.path("excluded").asBoolean())
        assertTrue(shopping.path("hasUnknownAmount").asBoolean())
        assertTrue(shopping.path("amount").isNull)
        assertTrue(shopping.path("foodMasterId").isNull)
        assertEquals("[\"name\",\"追加材料\",\"g\"]", shopping.path("aggregationKey").asText())
        assertTrue(shopping.path("recipeUsages").single().path("recipeId").isNull)
        assertEquals("料理", shopping.path("recipeUsages").single().path("recipeNameSnapshot").asText())
        assertTrue(d.path("shoppingLists").single().path("needsRegeneration").asBoolean())
        assertTrue(d.path("shoppingLists").single().path("sourceFingerprint").isNull)
        assertEquals(listOf("2026-09-28", "2026-10-03", "2026-10-04"), d.path("menuDays").map { it.path("date").asText() })
        assertTrue(d.path("menuDays").drop(1).all { it.path("noMeal").asBoolean() })
        assertEquals("旧メモ", d.path("menuDays").first().path("noMealMemo").asText())
    }

    @Test fun knownShoppingQuantityConvertsWithoutLosingCheck() {
        val input = legacy()
        row(input, "shoppingItems").put("amount", BigDecimal("2.500"))
        val item = ready(input).document.path("data").path("shoppingItems").single()
        assertEquals(0, BigDecimal("2.500").compareTo(BigDecimal(item.path("amount").asText())))
        assertFalse(item.path("hasUnknownAmount").asBoolean())
        assertTrue(item.path("checked").asBoolean())
    }

    @Test fun missingAndEmptyClassificationWarnButDoNotReject() {
        for (missing in listOf(true, false)) {
            val input = legacy()
            if (missing) row(input, "userFoodMasters").remove("foodGroups") else row(input, "userFoodMasters").putArray("foodGroups")
            val result = ready(input)
            assertTrue(result.warnings.any { it.contains("食品群") })
            assertTrue(result.document.path("data").path("userFoodMasters").single().path("foodGroups").isEmpty)
        }
    }

    @Test fun deletedRecipesAreRetiredWhenReferencedAndRemovedOnlyWhenUnreferenced() {
        val input = legacy()
        row(input, "recipes").put("status", "deleted")
        val second = row(input, "recipes").deepCopy().put("id", id(50)).put("name", "未使用料理").put("normalizedName", "未使用料理")
        array(input, "recipes").add(second)
        array(input, "recipeIngredients").add(row(input, "recipeIngredients").deepCopy().put("id", id(51)).put("recipeId", id(50)))
        val data = ready(input).document.path("data")
        assertEquals(1, data.path("recipes").size())
        assertEquals("retired", data.path("recipes").single().path("proposalPriority").asText())
        assertEquals(1, data.path("recipeIngredients").size())
        assertEquals(R, data.path("historyItems").single().path("recipeId").asText())
    }

    @Test fun legacyMissingOrPartialRequiredMealsAndUnneededMealsWithRecipesAreRejected() {
        for (mode in 0..2) {
            val input = legacy()
            when (mode) {
                0 -> array(input, "menuItems").removeAll()
                1 -> row(input, "menuItems").put("role", "main")
                2 -> row(input, "menuDays").put("noMeal", true)
            }
            reject(input, "献立構成")
        }
    }

    @Test fun duplicateIdsAndDuplicateActualRecipesAreRejectedBeforeConversion() {
        val duplicate = legacy()
        array(duplicate, "recipes").add(row(duplicate, "recipes").deepCopy())
        reject(duplicate, "重複")
        val actual = legacy()
        array(actual, "historyItems").add(row(actual, "historyItems").deepCopy().put("id", id(60)))
        reject(actual, "重複")
    }

    @Test fun unknownBuiltinAndMissingRecipeReferencesAreRejected() {
        val food = legacy()
        row(food, "recipeIngredients").put("foodMasterId", "builtin-missing")
        reject(food, "材料参照")
        val recipe = legacy()
        row(recipe, "menuItems").put("recipeId", id(99))
        reject(recipe, "参照")
    }

    @Test fun aliasCollisionsAndWrongNormalizationAreRejected() {
        val input = legacy()
        row(input, "userFoodMasters").putArray("aliases").add("ご飯")
        reject(input, "競合")
        val normalized = legacy()
        row(normalized, "recipeIngredients").put("normalizedName", "偽の名称")
        reject(normalized, "正規化")
    }

    @Test fun legacyHistoryDateMustExistAndMatchItsId() {
        val impossible = legacy()
        row(impossible, "cookingHistories").put("id", "history-2026-02-30")
        row(impossible, "historyItems").put("historyId", "history-2026-02-30")
        assertTrue(parse(impossible) is BackupFormatResult.Rejected)
        val mismatched = legacy()
        row(mismatched, "cookingHistories").put("cookedDate", "2026-09-29")
        reject(mismatched, "一致")
    }

    @Test fun duplicateLegacyShoppingKeysAreNotSilentlyMerged() {
        val input = legacy()
        array(input, "shoppingItems").add(row(input, "shoppingItems").deepCopy().put("id", id(61)).put("checked", false))
        reject(input, "集約キー")
    }

    @Test fun currentMixedAndUnknownOnlyAmountsSurviveRoundTrip() {
        for (amount in listOf("2.5", null)) {
            val input = current()
            if (amount == null) row(input, "shoppingItems").putNull("amount") else row(input, "shoppingItems").put("amount", amount)
            val first = ready(input).document
            val second = ready(first).document
            assertEquals(first, second)
            assertTrue(row(second, "shoppingItems").path("hasUnknownAmount").asBoolean())
            assertTrue(row(second, "shoppingItems").path("checked").asBoolean())
        }
    }

    @Test fun nullAmountWithoutUnknownFlagAndMismatchedAggregationKeysAreRejected() {
        val amount = current()
        row(amount, "shoppingItems").put("hasUnknownAmount", false)
        reject(amount, "形式")
        val key = current()
        row(key, "shoppingItems").put("aggregationKey", "unrelated")
        reject(key, "集約キー")
    }

    @Test fun currentUnconfiguredDayIsAllowedButCannotBeLocked() {
        val input = current()
        array(input, "menuItems").removeAll()
        assertTrue(parse(input) is BackupFormatResult.Ready)
        row(input, "menuDays").put("dayLocked", true)
        reject(input, "固定")
    }

    @Test fun requiredWeekBoundaryAndFixedRecipeCategoryAreValidated() {
        val week = current()
        row(week, "weeklyMenus").put("weekStart", "2026-09-29")
        reject(week, "月曜日")
        val date = current()
        row(date, "menuDays").put("date", "2026-09-27")
        reject(date, "範囲外")
        val category = current()
        row(category, "menuItems").put("locked", true)
        row(category, "recipes").put("category", "soup")
        reject(category, "カテゴリ")
    }

    @Test fun cookedOutcomeMustReferToCorrectRecipeAndDate() {
        val input = current()
        array(input, "planOutcomes").addObject().put("id", id(70)).put("menuItemId", I).put("state", "cooked")
            .put("historyItemId", H).put("updatedAt", NOW)
        assertTrue(parse(input) is BackupFormatResult.Ready)
        row(input, "cookingHistories").put("id", "history-2026-09-29").put("cookedDate", "2026-09-29")
        row(input, "historyItems").put("historyId", "history-2026-09-29")
        reject(input, "調理日")
    }

    @Test fun postponementRequiresBidirectionalSourceOutcomeAndTargetDate() {
        val input = current()
        array(input, "planOutcomes").addObject().put("id", id(70)).put("menuItemId", I).put("state", "postponed")
            .putNull("historyItemId").put("updatedAt", NOW)
        reject(input, "延期予定")
        array(input, "postponedPlans").addObject().put("id", id(71)).put("sourceMenuItemId", I).put("recipeId", R)
            .put("targetDate", "2026-10-03").put("state", "pending").putNull("targetMenuItemId").put("updatedAt", NOW)
        assertTrue(parse(input) is BackupFormatResult.Ready)
        array(input, "planOutcomes").removeAll()
        reject(input, "結果")
    }

    @Test fun typedIncompleteRecipeDraftPreservesHistoricConflictsAndBuildsReferenceIndex() {
        val input = current()
        addRecipeDraft(input)
        val result = ready(input)
        assertEquals(setOf(BackupDraftReference(id(80), "recipe", R), BackupDraftReference(id(80), "food", F)), result.draftReferences)
        val preserved = ready(result.document)
        assertEquals(result.document, preserved.document)
        assertEquals("統合前から変更あり", row(preserved.document, "drafts").path("payload").path("conflicts").single().path("reason").asText())
    }

    @Test fun missingDraftLiveReferenceIsRejectedWhileHistoricConflictRemainsAllowed() {
        val input = current()
        addRecipeDraft(input)
        (row(input, "drafts").path("payload").path("form").path("ingredients").first() as ObjectNode).put("foodMasterId", id(99))
        reject(input, "下書き")
    }

    @Test fun unsupportedMasterVersionsAndBackupVersionsDoNotProduceCandidates() {
        val input = legacy()
        input.put("masterVersion", "unmapped")
        reject(input, "ID変換表")
        input.put("schemaVersion", "9.0")
        reject(input, "対応していない")
    }

    @Test fun streamsRejectFailuresAndOversizeWithoutProducingRestoreCandidate() {
        val stream = object : java.io.InputStream() { override fun read(): Int = throw java.io.IOException("読込断絶") }
        val result = adapter().read(stream) as BackupFormatResult.Rejected
        assertEquals("読込断絶", result.message)
    }

    @Test fun masterMappingsChangeLiveReferencesAndAggregationKeysBeforeValidation() {
        val input = current()
        input.put("masterVersion", "old")
        row(input, "recipeIngredients").put("foodMasterId", "builtin-old-rice")
        row(input, "shoppingItems").put("foodMasterId", "builtin-old-rice")
            .put("aggregationKey", "[\"food\",\"builtin-old-rice\",\"g\"]")
        val mappedAdapter = BackupFormatAdapter(legacySchema, currentSchema,
            listOf(BackupPreset("builtin-rice", "米", listOf("ご飯"))), "1.0.0",
            mapOf("old" to mapOf("builtin-old-rice" to "builtin-rice")))
        val result = mappedAdapter.parse(mapper.writeValueAsBytes(input))
        assertTrue(result.toString(), result is BackupFormatResult.Ready)
        val document = (result as BackupFormatResult.Ready).document
        assertEquals("builtin-rice", row(document, "recipeIngredients").path("foodMasterId").asText())
        assertEquals("[\"food\",\"builtin-rice\",\"g\"]", row(document, "shoppingItems").path("aggregationKey").asText())
        assertEquals("old", input.path("masterVersion").asText())
        assertEquals("builtin-old-rice", row(input, "recipeIngredients").path("foodMasterId").asText())
    }

    @Test fun localCandidateReferencesAreValidatedAndDoNotCreateFoodRows() {
        val input = current()
        addRecipeDraft(input)
        val payload = row(input, "drafts").path("payload") as ObjectNode
        val draftIngredient = payload.path("form").path("ingredients").first() as ObjectNode
        draftIngredient.putNull("foodMasterId").put("foodCandidateKey", "pending-food")
        reject(input, "材料候補")
        payload.withArray("foodCandidates").addObject().put("localKey", "pending-food").put("name", "")
            .putArray("aliases")
        val candidate = payload.path("foodCandidates").first() as ObjectNode
        candidate.putArray("foodGroups")
        candidate.putArray("seasonMonths")
        candidate.put("baseUnit", "").put("isMainIngredient", false)
        val result = ready(input)
        assertEquals(1, result.document.path("data").path("userFoodMasters").size())
        assertEquals(setOf(BackupDraftReference(id(80), "recipe", R)), result.draftReferences)
    }

    private fun addRecipeDraft(root: ObjectNode) {
        array(root, "drafts").add(mapper.readTree("""{
          "id":"${id(80)}","kind":"recipe","targetKey":"$R","payloadVersion":1,"updatedAt":"$NOW",
          "baseRevisions":[{"entityType":"recipe","entityId":"$R","revision":1}],
          "payload":{"foodCandidates":[],"conflicts":[{"entityType":"food","entityId":"${id(99)}","reason":"統合前から変更あり"}],
            "form":{"name":"","category":"","cookingTimeMinutes":"未入力","effortLevel":"normal","sourceUrl":"https:","memo":"","proposalPriority":"normal",
              "ingredients":[{"localKey":"row1","name":"","amount":"未完成","unit":"","note":"","foodMasterId":"$F","foodCandidateKey":null}]}}
        }"""))
    }

    private fun current() = ready(legacy()).document
    private fun parse(root: JsonNode) = adapter().parse(mapper.writeValueAsBytes(root))
    private fun ready(root: JsonNode): BackupFormatResult.Ready {
        val result = parse(root)
        assertTrue(result.toString(), result is BackupFormatResult.Ready)
        return result as BackupFormatResult.Ready
    }
    private fun reject(root: JsonNode, text: String) {
        val result = parse(root)
        assertTrue(result.toString(), result is BackupFormatResult.Rejected)
        assertTrue((result as BackupFormatResult.Rejected).message, result.message.contains(text))
    }
    private fun row(root: JsonNode, name: String) = root.path("data").path(name).first() as ObjectNode
    private fun array(root: JsonNode, name: String) = root.path("data").path(name) as ArrayNode

    private fun legacy(): ObjectNode = mapper.readTree("""{
      "schemaVersion":"1.0","fileType":"full-backup","createdAt":"$NOW","appVersion":"1.00","masterVersion":"1.0.0",
      "data":{
        "recipes":[{"id":"$R","name":"料理","normalizedName":"料理","category":"one_dish","cookingTimeMinutes":20,"effortLevel":"normal","sourceUrl":null,"memo":"","lastCookedAt":"2026-09-28","initialLastCookedAt":"2026-09-01","nutritionGroups":["meat"],"status":"active","deletedAt":null,"createdAt":"$NOW","updatedAt":"$NOW"}],
        "recipeIngredients":[{"id":"${id(2)}","recipeId":"$R","order":0,"name":"追加材料","normalizedName":"追加材料","amount":0.12345678901234567890123456789,"unit":"g","note":"注記","foodMasterId":"$F"}],
        "weeklyMenus":[{"id":"$W","weekStart":"2026-09-28","createdAt":"$NOW","updatedAt":"$NOW"}],
        "menuDays":[{"id":"$D","weeklyMenuId":"$W","date":"2026-09-28","noMeal":false,"noMealReason":"旧理由","noMealMemo":"旧メモ"}],
        "menuItems":[{"id":"$I","menuDayId":"$D","recipeId":"$R","recipeNameSnapshot":"料理","role":"one_dish"}],
        "cookingHistories":[{"id":"history-2026-09-28","cookedDate":"2026-09-28","createdAt":"$NOW","updatedAt":"$NOW"}],
        "historyItems":[{"id":"$H","historyId":"history-2026-09-28","recipeId":"$R","recipeNameSnapshot":"料理","role":"one_dish"}],
        "shoppingLists":[{"id":"${id(8)}","weekStart":"2026-09-28","sourceMenuUpdatedAt":"$NOW","createdAt":"$NOW","updatedAt":"$NOW"}],
        "shoppingItems":[{"id":"${id(9)}","shoppingListId":"${id(8)}","name":"追加材料","normalizedName":"追加材料","amount":null,"unit":"g","recipeNames":["料理"],"checked":true,"excluded":true}],
        "userFoodMasters":[{"id":"$F","name":"追加材料","normalizedName":"追加材料","aliases":["Ａ Ｂ"],"foodGroups":["protein","vegetable"],"seasonMonths":[1,12],"salesAmount":100,"salesUnit":"g","isMainIngredient":true,"userEditable":true,"createdAt":"$NOW","updatedAt":"$NOW"}],
        "settings":{"backupReminderIntervalDays":30,"lastBackupAt":null,"holidayDataVersion":"1.0.0"}
      }
    }""") as ObjectNode

    companion object {
        const val NOW = "2026-10-03T00:00:00Z"
        private fun id(n: Int) = "00000000-0000-4000-8000-${n.toString().padStart(12, '0')}"
        private val R = id(1)
        private val F = id(3)
        private val W = id(4)
        private val D = id(5)
        private val I = id(6)
        private val H = id(7)
    }
}
