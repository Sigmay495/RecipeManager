package jp.local.recipemanager.data.local.v2

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.core.json.JsonInput
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate

/** Shared deletion protection and reference rewriting. Called inside the caller's write transaction. */
internal object DraftFoodMergeSupport {
    fun references(row: DraftEntityV2): Set<DraftReferenceEntityV2> = parse(row).refs
    private data class Key(val type: String, val id: String)
    private data class Parsed(val row: DraftEntityV2, val payload: ObjectNode, val bases: ArrayNode, val refs: Set<DraftReferenceEntityV2>)
    private val mapper get() = JsonInput.mapper()

    suspend fun referencesFood(database: RecipeManagerDatabaseV2, id: String): Boolean =
        checkedDrafts(database).any { draft ->
            draft.refs.any { it.entityType == "food" && it.entityId == id } ||
                draft.bases.any { it.text("entityType") == "food" && it.text("entityId") == id }
        }

    suspend fun referencesRecipe(database: RecipeManagerDatabaseV2, id: String): Boolean =
        checkedDrafts(database).any { draft ->
            draft.refs.any { it.entityType == "recipe" && it.entityId == id } ||
                draft.bases.any { it.text("entityType") == "recipe" && it.text("entityId") == id }
        }

    suspend fun merge(
        database: RecipeManagerDatabaseV2,
        sourceId: String,
        targetId: String,
        sourceRevision: Long,
        targetRevisionBefore: Long,
        targetRevisionAfter: Long,
        changedRecipeRevisions: Map<String, Pair<Long, Long>>,
        now: String,
    ) {
        val drafts = checkedDrafts(database)
        val before = revisions(database).toMutableMap().apply {
            put(Key("food", sourceId), sourceRevision)
            put(Key("food", targetId), targetRevisionBefore)
            changedRecipeRevisions.forEach { (id, pair) -> put(Key("recipe", id), pair.first) }
        }
        val dao = database.referenceDao()
        drafts.forEach { draft ->
            val affected = draft.refs.any { it.entityType == "food" && it.entityId == sourceId } || draft.bases.any {
                (it.text("entityType") == "food" && (it.text("entityId") == sourceId ||
                    (targetRevisionBefore != targetRevisionAfter && it.text("entityId") == targetId))) ||
                    (it.text("entityType") == "recipe" && it.text("entityId") in changedRecipeRevisions)
            }
            if (!affected) return@forEach
            val conflicts = draft.payload.path("conflicts") as ArrayNode
            val conflicting = conflicts.map { Key(it.text("entityType"), it.text("entityId")) }.toMutableSet()
            draft.bases.forEach { base ->
                val key = Key(base.text("entityType"), base.text("entityId"))
                if (before[key] != base.path("revision").longValue()) {
                    conflicting.add(key)
                    addConflict(conflicts, key, "材料統合前から基準情報が変更されています")
                }
            }
            forms(draft.row.kind, draft.payload).forEach { form ->
                form.path("ingredients").forEach { ingredient ->
                    if (ingredient.path("foodMasterId").asText() == sourceId) (ingredient as ObjectNode).put("foodMasterId", targetId)
                }
            }
            val combined = linkedMapOf<Key, ObjectNode>()
            draft.bases.forEach { node ->
                val base = node as ObjectNode
                val oldKey = Key(base.text("entityType"), base.text("entityId"))
                val wasConflicting = oldKey in conflicting
                if (oldKey.type == "food" && oldKey.id == sourceId) base.put("entityId", targetId)
                if (!wasConflicting) {
                    when {
                        oldKey.type == "food" && oldKey.id in setOf(sourceId, targetId) -> base.put("revision", targetRevisionAfter)
                        oldKey.type == "recipe" && oldKey.id in changedRecipeRevisions -> base.put("revision", changedRecipeRevisions.getValue(oldKey.id).second)
                    }
                }
                val key = Key(base.text("entityType"), base.text("entityId"))
                val previous = combined[key]
                if (previous == null) combined[key] = base
                else if (previous.path("revision") != base.path("revision")) {
                    // Keep an outdated baseline and a visible conflict; never normalize it to the current value.
                    if (wasConflicting) combined[key] = base
                    addConflict(conflicts, key, "材料統合で基準情報が競合しています")
                }
            }
            val bases = mapper.createArrayNode().apply { combined.values.forEach(::add) }
            val oldTime = Instant.parse(draft.row.updatedAt)
            val currentTime = Instant.parse(now)
            val updatedAt = (if (currentTime <= oldTime) oldTime.plusNanos(1) else currentTime).toString()
            val updated = draft.row.copy(payloadJson = draft.payload.toString(), baseRevisionsJson = bases.toString(), updatedAt = updatedAt)
            val refs = parse(updated).refs
            dao.updateDraft(updated)
            dao.deleteReferences(updated.id)
            if (refs.isNotEmpty()) dao.insertReferences(refs.toList())
        }
    }

    private suspend fun checkedDrafts(database: RecipeManagerDatabaseV2): List<Parsed> {
        val dao = database.referenceDao()
        val parsed = dao.drafts().map(::parse)
        val index = dao.references().toSet()
        require(index == parsed.flatMap { it.refs }.toSet()) { "下書きの参照索引と内容が一致しません" }
        val live = revisions(database)
        parsed.forEach { draft ->
            draft.refs.forEach { require(Key(it.entityType, it.entityId) in live) { "下書きの参照が切れています" } }
            draft.bases.forEach { require(Key(it.text("entityType"), it.text("entityId")) in live) { "下書きの基準情報の参照が切れています" } }
        }
        return parsed
    }

    private suspend fun revisions(database: RecipeManagerDatabaseV2): Map<Key, Long> {
        val dao = database.referenceDao()
        return buildMap {
            dao.recipes().forEach { put(Key("recipe", it.id), it.revision) }
            dao.foods().forEach { put(Key("food", it.id), it.revision) }
            dao.menus().forEach { put(Key("menu", it.id), it.revision) }
            dao.days().forEach { put(Key("menuDay", it.id), it.revision) }
        }
    }

    private fun parse(row: DraftEntityV2): Parsed {
        require(row.payloadVersion == 1) { "対応していない下書きの版です" }
        val payload = JsonInput.parse(row.payloadJson.toByteArray(Charsets.UTF_8), JsonInput.BACKUP_LIMIT, mapper) as? ObjectNode
            ?: error("下書き内容がオブジェクトではありません")
        val bases = JsonInput.parse(row.baseRevisionsJson.toByteArray(Charsets.UTF_8), JsonInput.BACKUP_LIMIT, mapper) as? ArrayNode
            ?: error("下書き基準情報が配列ではありません")
        validateShape(row, payload, bases)
        require(payload.path("conflicts").isArray) { "下書きの競合情報が不正です" }
        payload.path("conflicts").forEach { it.text("entityType"); it.text("entityId"); it.text("reason") }
        val baseKeys = mutableSetOf<Key>()
        bases.forEach {
            val key = Key(it.text("entityType"), it.text("entityId"))
            require(key.type in setOf("recipe", "food", "menu", "menuDay") && key.id.isNotBlank() &&
                it.path("revision").isIntegralNumber && it.path("revision").canConvertToLong() && it.path("revision").longValue() > 0 && baseKeys.add(key)) {
                "下書き基準情報が不正または重複しています"
            }
        }
        val refs = linkedSetOf<DraftReferenceEntityV2>()
        fun add(type: String, id: String) {
            require(id.isNotBlank()) { "下書きの参照IDが空です" }
            refs.add(DraftReferenceEntityV2(row.id, type, id))
        }
        if (row.kind == "recipe" && row.targetKey != "new") add("recipe", row.targetKey)
        forms(row.kind, payload).forEach { form ->
            require(form.path("ingredients").isArray) { "下書きの材料行が不正です" }
            form.path("ingredients").forEach {
                val food = it.get("foodMasterId") ?: error("下書きの材料参照がありません")
                if (!food.isNull) { require(food.isTextual); add("food", food.textValue()) }
            }
        }
        if (row.kind == "import") payload.path("entries").forEach { entry ->
            require(entry.path("duplicateRecipeIds").isArray) { "下書きの重複候補が不正です" }
            entry.path("duplicateRecipeIds").forEach { require(it.isTextual); add("recipe", it.textValue()) }
        }
        if (row.kind == "menu") {
            require(payload.path("days").isArray) { "提案下書きの日が不正です" }
            payload.path("days").forEach { day ->
                require(day.path("items").isArray) { "提案下書きの料理が不正です" }
                day.path("items").forEach { add("recipe", it.text("recipeId")) }
            }
        }
        return Parsed(row, payload, bases, refs)
    }

    private fun forms(kind: String, payload: ObjectNode): List<JsonNode> = when (kind) {
        "recipe" -> listOf(payload.path("form").also { require(it.isObject) { "レシピ下書きの入力が不正です" } })
        "import" -> {
            require(payload.path("entries").isArray) { "Import下書きの行が不正です" }
            payload.path("entries").map { it.path("form").also { form -> require(form.isObject) { "Import下書きの入力が不正です" } } }
        }
        "menu" -> emptyList()
        else -> error("対応していない下書き種別です")
    }

    private fun addConflict(conflicts: ArrayNode, key: Key, reason: String) {
        if (conflicts.none { it.path("entityType").asText() == key.type && it.path("entityId").asText() == key.id && it.path("reason").asText() == reason })
            conflicts.addObject().put("entityType", key.type).put("entityId", key.id).put("reason", reason)
    }

    private fun JsonNode.text(key: String): String = path(key).also { require(it.isTextual) { "下書きの$key が文字列ではありません" } }.textValue()

    /** Only the persisted, known payload shape may contain live references; unknown fields fail closed. */
    private fun validateShape(row: DraftEntityV2, payload: ObjectNode, bases: ArrayNode) {
        fun fields(node: JsonNode, vararg names: String) {
            require(node.isObject && node.fieldNames().asSequence().toSet() == names.toSet()) { "下書きの項目構成が不正です" }
        }
        fun strings(array: JsonNode, allowBlank: Boolean = false) {
            require(array.isArray && array.all { it.isTextual && (allowBlank || it.textValue().isNotBlank()) }) { "下書きの文字列配列が不正です" }
        }
        fun unique(nodes: JsonNode, field: String) {
            require(nodes.isArray && nodes.map { it.text(field) }.let { it.all(String::isNotBlank) && it.toSet().size == it.size }) {
                "下書きの$field が不正または重複しています"
            }
        }
        when (row.kind) {
            "recipe" -> fields(payload, "form", "foodCandidates", "conflicts")
            "import" -> {
                require(row.targetKey == "singleton") { "Import下書きの保存キーが不正です" }
                fields(payload, "sourceJson", "entries", "foodCandidates", "conflicts")
                payload.text("sourceJson")
                unique(payload.path("entries"), "localKey")
                payload.path("entries").forEach {
                    fields(it, "localKey", "form", "decision", "validationState", "errors", "duplicateRecipeIds")
                    require(it.text("decision") in setOf("add", "skip") && it.text("validationState") in setOf("unchecked", "valid", "error"))
                    strings(it.path("errors")); strings(it.path("duplicateRecipeIds"))
                }
            }
            "menu" -> {
                fields(payload, "days", "warnings", "seed", "conflicts")
                val start = LocalDate.parse(row.targetKey)
                require(start.dayOfWeek == DayOfWeek.MONDAY && start.toString() == row.targetKey && payload.text("seed").toLongOrNull() != null)
                strings(payload.path("warnings"))
                val days = payload.path("days")
                require(days.isArray && days.size() == 7 && days.map { it.text("date") }.toSet() == (0L..6L).map { start.plusDays(it).toString() }.toSet())
                days.forEach { day ->
                    fields(day, "date", "noMeal", "requirementOrigin", "dayLocked", "items")
                    require(day.path("noMeal").isBoolean && day.path("dayLocked").isBoolean && day.text("requirementOrigin") in setOf("explicit", "default", "legacy"))
                    unique(day.path("items"), "localKey")
                    unique(day.path("items"), "role")
                    day.path("items").forEach {
                        fields(it, "localKey", "recipeId", "role", "locked")
                        require(it.text("recipeId").isNotBlank() && it.text("role") in setOf("one_dish", "main", "side", "soup") && it.path("locked").isBoolean)
                    }
                }
            }
            else -> error("対応していない下書き種別です")
        }
        val candidates = if (row.kind == "menu") mapper.createArrayNode() else payload.path("foodCandidates")
        unique(candidates, "localKey")
        candidates.forEach {
            fields(it, "localKey", "name", "aliases", "foodGroups", "baseUnit", "seasonMonths", "isMainIngredient")
            it.text("name"); it.text("baseUnit"); strings(it.path("aliases"), allowBlank = true); strings(it.path("foodGroups"))
            require(it.path("foodGroups").all { group -> group.asText() in setOf("grain", "meat", "seafood", "egg", "dairy", "soy", "vegetable", "mushroom", "seaweed", "fruit", "fat", "seasoning") })
            require(it.path("foodGroups").toSet().size == it.path("foodGroups").size())
            require(it.path("seasonMonths").isArray && it.path("seasonMonths").all { month -> month.isIntegralNumber && month.canConvertToInt() && month.intValue() in 1..12 } && it.path("isMainIngredient").isBoolean)
            require(it.path("seasonMonths").toSet().size == it.path("seasonMonths").size())
        }
        val candidateKeys = candidates.map { it.text("localKey") }.toSet()
        forms(row.kind, payload).forEach { form ->
            fields(form, "name", "category", "cookingTimeMinutes", "effortLevel", "sourceUrl", "memo", "proposalPriority", "ingredients")
            listOf("name", "cookingTimeMinutes", "sourceUrl", "memo").forEach { form.text(it) }
            require(form.text("category") in setOf("", "one_dish", "main", "side", "soup") &&
                form.text("effortLevel") in setOf("easy", "normal", "hard") && form.text("proposalPriority") in setOf("high", "normal", "low", "retired"))
            unique(form.path("ingredients"), "localKey")
            form.path("ingredients").forEach {
                fields(it, "localKey", "name", "amount", "unit", "note", "foodMasterId", "foodCandidateKey")
                listOf("name", "amount", "unit", "note").forEach { key -> it.text(key) }
                val food = it.path("foodMasterId"); val candidate = it.path("foodCandidateKey")
                require((food.isNull || food.isTextual && food.textValue().isNotBlank()) &&
                    (candidate.isNull || candidate.isTextual && candidate.textValue() in candidateKeys) && (food.isNull || candidate.isNull))
            }
        }
        require(payload.path("conflicts").isArray)
        payload.path("conflicts").forEach {
            fields(it, "entityType", "entityId", "reason")
            require(it.text("entityType") in setOf("recipe", "food", "menu", "menuDay") && it.text("entityId").isNotBlank() && it.text("reason").isNotBlank())
        }
        bases.forEach { fields(it, "entityType", "entityId", "revision") }
    }
}
