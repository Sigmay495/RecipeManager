package jp.local.recipemanager.data.local.v2

import android.content.Context
import androidx.room.withTransaction
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.feature.recipeimport.ImportLoadResult
import jp.local.recipemanager.feature.recipeimport.RecipeImportParser
import java.io.InputStream
import java.math.BigDecimal
import java.net.URI
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

data class ImportDuplicateV2(val recipeId: String, val name: String, val retired: Boolean, val reason: String)
data class ImportEntryReviewV2(val localKey: String, val errors: List<String>, val duplicates: List<ImportDuplicateV2>, val duplicateEntryKeys: Set<String>)
data class ImportReviewV2(val draft: DraftEntityV2, val entries: List<ImportEntryReviewV2>)
data class ImportCommitV2(val addedRecipeIds: List<String>, val skipped: Int)
data class MenuDraftReviewV2(val draft: DraftEntityV2, val evaluation: ProposalEvaluationV2)

/** Commits only reviewed drafts. Every write, including candidate food creation, is atomic. */
class DraftCommitRepositoryV2(
    private val database: RecipeManagerDatabaseV2,
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val mapper = JsonInput.mapper()
    private val drafts = DraftRepositoryV2(database, context, clock)
    private val foods = FoodRepositoryV2(database)
    private val recipes = RecipeRepositoryV2(database, clock)

    suspend fun beginImport(stream: InputStream, expectedUpdatedAt: String? = null, confirmedReplace: Boolean = false): ImportReviewV2 =
        beginImport(JsonInput.read(stream, JsonInput.IMPORT_LIMIT), expectedUpdatedAt, confirmedReplace)

    suspend fun beginImport(bytes: ByteArray, expectedUpdatedAt: String? = null, confirmedReplace: Boolean = false): ImportReviewV2 {
        val parsed = RecipeImportParser(context).parse("import.json", bytes)
        require(parsed is ImportLoadResult.Success) { (parsed as ImportLoadResult.Failure).message }
        val source = JsonInput.parse(bytes, JsonInput.IMPORT_LIMIT, mapper)
        return database.withTransaction {
            val old = drafts.find("import", "singleton")
            require(old == null || confirmedReplace) { "確認中のImportがあります。置換の確認が必要です" }
            val payload = mapper.createObjectNode().apply {
                put("sourceJson", bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
                putArray("foodCandidates"); putArray("conflicts")
            }
            val entries = payload.putArray("entries")
            source.path("recipes").forEachIndexed { index, node ->
                entries.addObject().apply {
                    put("localKey", uuid()); set<JsonNode>("form", formFromImport(node))
                    val errors = parsed.preview.candidates[index].errors
                    put("decision", if (errors.isEmpty()) "add" else "skip")
                    put("validationState", if (errors.isEmpty()) "valid" else "error")
                    putArray("errors").also { a -> errors.forEach(a::add) }; putArray("duplicateRecipeIds")
                }
            }
            resolveIngredients(payload)
            val reviews = review(payload)
            entries.forEach { entry ->
                val result = reviews.single { it.localKey == entry.path("localKey").asText() }
                // Original Schema errors remain visible until the user explicitly edits and revalidates.
                if (entry.path("errors").isEmpty) setReview(entry as ObjectNode, result)
                else (entry as ObjectNode).put("validationState", "error")
                if (result.duplicates.isNotEmpty() || result.duplicateEntryKeys.isNotEmpty()) entry.put("decision", "skip")
            }
            val row = drafts.save("import", "singleton", payload, drafts.captureBases("import", "singleton", payload), expectedUpdatedAt)
            ImportReviewV2(row, review(payload).map { result ->
                val stored = entries.single { it.path("localKey").asText() == result.localKey }
                result.copy(errors = (result.errors + stored.path("errors").map { it.asText() }).distinct())
            })
        }
    }

    suspend fun revalidateImport(id: String, expectedUpdatedAt: String): ImportReviewV2 = database.withTransaction {
        val row = database.draftDao().find(id) ?: error("下書きが見つかりません")
        require(row.kind == "import" && row.updatedAt == expectedUpdatedAt) { "Import下書きが変更されています" }
        // Resume checks the stored shape/index but does not discard a conflicting draft.
        drafts.resume(id)
        val payload = drafts.payload(row)
        resolveIngredients(payload)
        val reviews = review(payload)
        payload.path("entries").forEach { entry -> setReview(entry as ObjectNode, reviews.single { it.localKey == entry.path("localKey").asText() }) }
        val oldBases = drafts.bases(row).associateBy { it.entityType to it.entityId }
        val bases = drafts.captureBases(row.kind, row.targetKey, payload).map { oldBases[it.entityType to it.entityId] ?: it }
        val saved = drafts.save(row.kind, row.targetKey, payload, bases, row.updatedAt)
        ImportReviewV2(saved, reviews)
    }

    suspend fun confirmImport(id: String, expectedUpdatedAt: String, confirmed: Boolean, acknowledgedDuplicateKeys: Set<String> = emptySet()): ImportCommitV2 = database.withTransaction {
        require(confirmed) { "Import確定の確認が必要です" }
        val row = drafts.ready(id, expectedUpdatedAt, "import")
        val payload = drafts.payload(row)
        val reviews = review(payload)
        val entries = payload.path("entries").toList()
        require(entries.size in 1..100) { "Importは1～100件です" }
        val selected = entries.filter { it.path("decision").asText() == "add" }
        selected.forEach { entry ->
            val current = reviews.single { it.localKey == entry.path("localKey").asText() }
            require(current.errors.isEmpty()) { "修正が必要なレシピがあります: ${current.errors.joinToString()}" }
            require(entry.path("validationState").asText() == "valid" && entry.path("errors").isEmpty) { "修正後にImportを再検証してください" }
            require(entry.path("duplicateRecipeIds").map { it.asText() }.toSet() == current.duplicates.map { it.recipeId }.toSet()) { "重複候補が変わりました。再検証してください" }
            require((current.duplicates.isEmpty() && current.duplicateEntryKeys.isEmpty()) || current.localKey in acknowledgedDuplicateKeys) { "重複候補を確認し、新規追加またはスキップを選択してください" }
        }
        val candidateIds = mutableMapOf<String, String>()
        val added = selected.map { entry ->
            val input = input(entry.path("form"), payload, candidateIds, materialize = true, requireMaster = true)
            recipes.save(input.copy(proposalPriority = "normal")).id
        }
        drafts.remove(id)
        ImportCommitV2(added, entries.size - selected.size)
    }

    suspend fun confirmRecipe(id: String, expectedUpdatedAt: String, confirmed: Boolean): RecipeEntityV2 = database.withTransaction {
        require(confirmed) { "レシピ確定の確認が必要です" }
        val row = drafts.ready(id, expectedUpdatedAt, "recipe")
        val payload = drafts.payload(row)
        val target = row.targetKey.takeUnless { it == "new" }
        val base = target?.let { targetId -> drafts.bases(row).single { it.entityType == "recipe" && it.entityId == targetId }.revision }
        val data = input(payload.path("form"), payload, mutableMapOf(), materialize = true)
        val saved = recipes.save(data.copy(id = target, expectedRevision = base))
        drafts.remove(id)
        saved
    }

    suspend fun reviewMenu(id: String, expectedUpdatedAt: String): MenuDraftReviewV2 = database.withTransaction {
        val row = drafts.ready(id, expectedUpdatedAt, "menu")
        MenuDraftReviewV2(row, menuEvaluation(row))
    }

    /** Rechecks elapsed/result-protected days, fixed selections, warnings and all baselines before saving. */
    suspend fun confirmMenu(id: String, expectedUpdatedAt: String, confirmed: Boolean, acknowledgedWarnings: Set<String> = emptySet()): MenuWeekV2 = database.withTransaction {
        require(confirmed) { "提案採用の確認が必要です" }
        val row = drafts.ready(id, expectedUpdatedAt, "menu")
        val start = LocalDate.parse(row.targetKey)
        val today = LocalDate.now(clock)
        require(start >= today.minusDays(today.dayOfWeek.value - 1L)) { "過去週の提案は採用できません" }
        val payload = drafts.payload(row)
        val evaluation = menuEvaluation(row)
        require(evaluation.warnings.all { it in acknowledgedWarnings }) { "献立の警告を再確認してください" }
        val menus = MenuRepositoryV2(database, clock)
        val before = menus.week(start)
        val results = database.menuDao().outcomes().map { it.menuItemId }.toSet()
        payload.path("days").forEach { day ->
            val old = before.days.single { it.day.date == day.path("date").asText() }
            val proposedItems = day.path("items").map { Triple(it.path("recipeId").asText(), it.path("role").asText().uppercase(Locale.ROOT), it.path("locked").asBoolean()) }.toSet()
            val oldItems = old.items.map { Triple(it.recipeId, it.role, it.locked) }.toSet()
            if (LocalDate.parse(old.day.date) < today || old.items.any { it.id in results }) {
                require(old.day.noMeal == day.path("noMeal").asBoolean() && old.day.dayLocked == day.path("dayLocked").asBoolean() && oldItems == proposedItems) {
                    "経過済み日または結果記録済み日が変更されています。提案を見直してください"
                }
            }
        }
        payload.path("days").sortedBy { it.path("date").asText() }.forEach { day ->
            val week = menus.week(start)
            val date = LocalDate.parse(day.path("date").asText())
            val old = week.days.single { it.day.date == date.toString() }
            menus.saveDay(MenuDayInputV2(date, day.path("noMeal").asBoolean(), day.path("items").map {
                MenuSelectionV2(it.path("recipeId").asText(), it.path("role").asText().uppercase(Locale.ROOT), it.path("locked").asBoolean())
            }, day.path("dayLocked").asBoolean(), day.path("requirementOrigin").asText()), week.menu?.revision,
                old.day.revision.takeIf { old.day.id.isNotEmpty() }, confirmDetach = false)
        }
        drafts.remove(id)
        menus.week(start)
    }

    private suspend fun menuEvaluation(row: DraftEntityV2): ProposalEvaluationV2 {
        val payload = drafts.payload(row)
        val request = MenuProposalRepositoryV2(database, clock)
            .evaluationRequest(LocalDate.parse(row.targetKey), payload.path("seed").asText().toLong())
        val days = payload.path("days").map { day ->
            ProposalDayV2(LocalDate.parse(day.path("date").asText()), day.path("noMeal").asBoolean(), day.path("dayLocked").asBoolean(), day.path("items").map {
                ProposalItemV2(it.path("recipeId").asText(), it.path("role").asText().uppercase(Locale.ROOT), it.path("locked").asBoolean())
            })
        }
        return ProposalEngineV2().evaluate(request, days)
    }

    private suspend fun review(payload: ObjectNode): List<ImportEntryReviewV2> {
        val entries = payload.path("entries").toList()
        val existing = database.recipeDao().all()
        val mainFoods = database.foodDao().all().filter { it.isMainIngredient }.map { it.id }.toSet()
        val existingMajors = existing.associate { record -> record.recipe.id to record.ingredients.mapNotNull { it.foodMasterId?.takeIf(mainFoods::contains) }.toSet() }
        val inputs = entries.associate { it.path("localKey").asText() to runCatching {
            input(it.path("form"), payload, mutableMapOf(), materialize = false, requireMaster = true).also(recipes::validateInput)
        } }
        val importedMajors = inputs.mapValues { (_, value) -> value.getOrNull()?.let { majorIds(it, mainFoods) }.orEmpty() }
        return entries.map { entry ->
            val key = entry.path("localKey").asText()
            val candidate = inputs.getValue(key).getOrNull()
            val errors = inputs.getValue(key).exceptionOrNull()?.let { listOf(it.message ?: "入力が不正です") }.orEmpty()
            val duplicates = if (candidate == null) emptyList() else existing.mapNotNull { record ->
                val reason = duplicateReason(candidate, importedMajors.getValue(key), record.recipe.name, record.recipe.sourceUrl, existingMajors.getValue(record.recipe.id))
                reason?.let { ImportDuplicateV2(record.recipe.id, record.recipe.name, record.recipe.proposalPriority == "retired", it) }
            }
            val peers = if (candidate == null) emptySet() else inputs.filter { (other, value) ->
                other != key && value.getOrNull()?.let { duplicateReason(candidate, importedMajors.getValue(key), it.name, it.sourceUrl, importedMajors.getValue(other)) != null } == true
            }.keys
            ImportEntryReviewV2(key, errors, duplicates, peers)
        }
    }

    private fun duplicateReason(input: RecipeInputV2, main: Set<String>, name: String, url: String?, otherMajor: Set<String>): String? {
        val sameName = TextNormalizer.normalize(input.name) == TextNormalizer.normalize(name)
        val sameUrl = normalizeUrl(input.sourceUrl)?.let { it == normalizeUrl(url) } == true
        return when {
            sameName && sameUrl -> "料理名とURLが一致"
            sameUrl -> "URLが一致"
            sameName && main.isNotEmpty() && main.intersect(otherMajor).size * 2 >= main.size -> "料理名と主要材料が一致"
            else -> null
        }
    }

    private fun majorIds(input: RecipeInputV2, mainFoods: Set<String>): Set<String> = input.ingredients.mapNotNull { ingredient ->
        when {
            ingredient.newFood?.isMainIngredient == true -> "name:${TextNormalizer.normalize(ingredient.newFood.name)}"
            ingredient.foodMasterId in mainFoods -> ingredient.foodMasterId
            else -> null
        }
    }.toSet()

    private suspend fun input(form: JsonNode, payload: ObjectNode, candidates: MutableMap<String, String>, materialize: Boolean, requireMaster: Boolean = false): RecipeInputV2 {
        val definitions = payload.path("foodCandidates").associateBy { it.path("localKey").asText() }
        val ingredients = form.path("ingredients").map { ingredient ->
            val candidateKey = ingredient.path("foodCandidateKey").takeUnless { it.isNull }?.asText()
            val definition = candidateKey?.let { definitions[it] ?: error("材料候補が見つかりません") }
            val newFood = definition?.let { foodInput(it) }
            if (newFood != null) validateCandidate(newFood)
            val foodId = ingredient.path("foodMasterId").takeUnless { it.isNull }?.asText()
                ?: candidateKey?.let { key -> if (materialize) candidates.getOrPutSuspend(key) { foods.save(newFood!!).id } else null }
            require(!requireMaster || foodId != null || newFood != null) { "未登録材料候補を確認してください" }
            val amountText = ingredient.path("amount").asText()
            val amount = if (amountText.isBlank()) null else amountText.toBigDecimalOrNull() ?: error("分量は数値で入力してください")
            RecipeIngredientInputV2(ingredient.path("name").asText(), amount, ingredient.path("unit").asText(), ingredient.path("note").asText(), foodId,
                if (!materialize) newFood else null)
        }
        val minutes = form.path("cookingTimeMinutes").asText()
        return RecipeInputV2(form.path("name").asText(), form.path("category").asText().uppercase(Locale.ROOT), ingredients,
            cookingTimeMinutes = if (minutes.isBlank()) null else minutes.toIntOrNull() ?: error("調理時間は整数で入力してください"),
            effortLevel = form.path("effortLevel").asText().uppercase(Locale.ROOT), sourceUrl = form.path("sourceUrl").asText().takeIf { it.isNotBlank() },
            memo = form.path("memo").asText(), proposalPriority = form.path("proposalPriority").asText())
    }

    private suspend fun resolveIngredients(payload: ObjectNode) {
        val candidates = payload.path("foodCandidates") as ArrayNode
        val names = database.foodDao().names().groupBy { it.normalizedName }.mapValues { (_, rows) ->
            val ids = rows.map { it.foodMasterId }.distinct()
            check(ids.size == 1) { "材料名・別名が競合しています" }
            ids.single()
        }
        val forms = payload.path("entries").map { it.path("form") }
        forms.forEach { form -> form.path("ingredients").forEach { node ->
            val ingredient = node as ObjectNode
            if (ingredient.path("foodMasterId").isNull && ingredient.path("foodCandidateKey").isNull && TextNormalizer.normalize(ingredient.path("name").asText()).isNotBlank()) {
                val found = names[TextNormalizer.normalize(ingredient.path("name").asText())]
                if (found != null) ingredient.put("foodMasterId", found)
                else {
                    val normalized = TextNormalizer.normalize(ingredient.path("name").asText())
                    val old = candidates.firstOrNull { TextNormalizer.normalize(it.path("name").asText()) == normalized }
                    val key = old?.path("localKey")?.asText() ?: uuid().also { id -> candidates.addObject().apply {
                        put("localKey", id); put("name", ingredient.path("name").asText()); putArray("aliases"); putArray("foodGroups")
                        put("baseUnit", ingredient.path("unit").asText()); putArray("seasonMonths"); put("isMainIngredient", true)
                    } }
                    ingredient.put("foodCandidateKey", key)
                }
            }
        } }
    }

    private fun formFromImport(node: JsonNode): ObjectNode = mapper.createObjectNode().apply {
        put("name", node.path("name").asText()); put("category", node.path("category").asText().takeIf { it in setOf("one_dish", "main", "side", "soup") } ?: "")
        put("cookingTimeMinutes", node.path("cookingTimeMinutes").takeUnless { it.isNull || it.isMissingNode }?.asText().orEmpty())
        put("effortLevel", node.path("effortLevel").asText("normal").takeIf { it in setOf("easy", "normal", "hard") } ?: "normal")
        put("sourceUrl", node.path("sourceUrl").takeUnless { it.isNull || it.isMissingNode }?.asText().orEmpty()); put("memo", node.path("memo").asText("")); put("proposalPriority", "normal")
        putArray("ingredients").also { a -> if (node.path("ingredients").isArray) node.path("ingredients").forEach { ingredient -> a.addObject().apply {
            put("localKey", uuid()); put("name", ingredient.path("name").asText())
            put("amount", ingredient.path("amount").takeUnless { it.isNull || it.isMissingNode }?.asText().orEmpty())
            put("unit", ingredient.path("unit").asText()); put("note", ingredient.path("note").asText("")); putNull("foodMasterId"); putNull("foodCandidateKey")
        } } }
    }
    private fun foodInput(node: JsonNode) = FoodInputV2(node.path("name").asText(), node.path("aliases").map { it.asText() },
        node.path("foodGroups").map { it.asText().uppercase(Locale.ROOT) }.toSet(), node.path("baseUnit").asText(),
        node.path("seasonMonths").map { it.asInt() }.toSet(), node.path("isMainIngredient").asBoolean())
    private fun validateCandidate(food: FoodInputV2) {
        require(TextNormalizer.normalize(food.name).isNotBlank() && food.baseUnit.length <= 20) { "材料候補の名前・単位を修正してください" }
        require(food.aliases.all { TextNormalizer.normalize(it).isNotBlank() }) { "材料候補の別名を修正してください" }
    }
    private fun setReview(entry: ObjectNode, review: ImportEntryReviewV2) {
        entry.put("validationState", if (review.errors.isEmpty()) "valid" else "error")
        entry.putArray("errors").also { a -> review.errors.forEach(a::add) }
        entry.putArray("duplicateRecipeIds").also { a -> review.duplicates.forEach { a.add(it.recipeId) } }
    }
    private fun normalizeUrl(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }?.let { raw -> runCatching {
        val uri = URI(raw).normalize()
        URI(uri.scheme.lowercase(Locale.ROOT), uri.userInfo, uri.host?.lowercase(Locale.ROOT), uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
    }.getOrNull() }
    private suspend fun MutableMap<String, String>.getOrPutSuspend(key: String, create: suspend () -> String): String = get(key) ?: create().also { put(key, it) }
    private fun uuid() = UUID.randomUUID().toString()
}
