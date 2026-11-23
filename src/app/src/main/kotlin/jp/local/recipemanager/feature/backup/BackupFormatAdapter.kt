package jp.local.recipemanager.feature.backup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialects
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import java.io.InputStream
import java.time.LocalDate
import java.util.UUID

data class BackupPreset(val id: String, val name: String, val aliases: List<String>)
data class BackupDraftReference(val draftId: String, val entityType: String, val entityId: String)

sealed interface BackupFormatResult {
    data class Ready(
        val document: ObjectNode,
        val warnings: List<String>,
        val draftReferences: Set<BackupDraftReference>,
    ) : BackupFormatResult
    data class Rejected(val message: String) : BackupFormatResult
}

/** Pure preflight adapter. It never writes a DB or mutates the supplied source document. */
class BackupFormatAdapter(
    legacySchemaDocument: JsonNode,
    currentSchemaDocument: JsonNode,
    private val presets: List<BackupPreset>,
    private val masterVersion: String,
    private val legacyMasterMappings: Map<String, Map<String, String>> = emptyMap(),
) {
    private val mapper = JsonInput.mapper()
    private val registry = SchemaRegistry.withDialect(Dialects.getDraft202012()) {
        it.schemaRegistryConfig(SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build())
    }
    private val legacySchema = run {
        JsonInput.requireLocalReferences(legacySchemaDocument)
        registry.getSchema(legacySchemaDocument)
    }
    private val currentSchema = run {
        JsonInput.requireLocalReferences(currentSchemaDocument)
        registry.getSchema(currentSchemaDocument)
    }

    init {
        require(presets.map { it.id }.toSet().size == presets.size) { "内蔵材料IDが重複しています" }
        require(presets.all { it.id.startsWith("builtin-") }) { "内蔵材料IDが不正です" }
    }

    fun read(stream: InputStream): BackupFormatResult = try {
        parse(JsonInput.read(stream, JsonInput.BACKUP_LIMIT))
    } catch (e: Exception) {
        BackupFormatResult.Rejected(e.message ?: "ファイルを読み込めませんでした")
    }

    fun parse(bytes: ByteArray): BackupFormatResult = try {
        val root = JsonInput.parse(bytes, JsonInput.BACKUP_LIMIT, mapper)
        require(root.isObject) { "バックアップの最上位はオブジェクトである必要があります" }
        val warnings = mutableListOf<String>()
        val candidate = when (root.path("schemaVersion").asText()) {
            "1.0" -> convertLegacy(root as ObjectNode, warnings)
            "2.0" -> root.deepCopy<ObjectNode>()
            else -> error("対応していないバックアップ形式です")
        }
        resolveMasterVersion(candidate)
        val errors = currentSchema.validate(candidate)
        require(errors.isEmpty()) {
            "バックアップ形式が不正です: ${errors.take(5).joinToString { "${it.instanceLocation}: ${it.message}" }}"
        }
        val references = BackupGraphValidator(presets).validate(candidate)
        candidate.path("data").path("userFoodMasters").filter { it.path("foodGroups").isEmpty }.forEach {
            warnings += "材料「${it.path("name").asText()}」の食品群が欠落しています。栄養評価に影響するため材料管理で確認してください。"
        }
        BackupFormatResult.Ready(candidate, warnings.distinct(), references)
    } catch (e: Exception) {
        BackupFormatResult.Rejected(e.message ?: "バックアップの検証に失敗しました")
    }

    private fun convertLegacy(source: ObjectNode, warnings: MutableList<String>): ObjectNode {
        val candidate = source.deepCopy()
        val data = candidate.path("data")
        // Only classification absence is permitted; all other structural faults still fail.
        data.path("userFoodMasters").forEach { node ->
            if (node.isObject && !node.has("foodGroups")) (node as ObjectNode).putArray("foodGroups")
        }
        val errors = legacySchema.validate(candidate)
        require(errors.isEmpty()) {
            "旧バックアップ形式が不正です: ${errors.take(5).joinToString { "${it.instanceLocation}: ${it.message}" }}"
        }
        resolveMasterVersion(candidate)
        BackupGraphValidator(presets).validate(candidate, legacy = true)
        val d = data as ObjectNode
        val usedRecipes = (d.path("menuItems") + d.path("historyItems")).map { it.path("recipeId").asText() }.toSet()
        val deleted = d.path("recipes").filter { it.path("status").asText() == "deleted" && it.path("id").asText() !in usedRecipes }
            .map { it.path("id").asText() }.toSet()
        val keptRecipes = mapper.createArrayNode()
        d.path("recipes").forEach { node ->
            val recipe = node as ObjectNode
            if (recipe.path("id").asText() !in deleted) {
                recipe.put("proposalPriority", if (recipe.path("status").asText() == "deleted") "retired" else "normal")
                recipe.put("revision", 1)
                recipe.remove(listOf("status", "deletedAt", "initialLastCookedAt", "lastCookedAt", "nutritionGroups"))
                keptRecipes.add(recipe)
            }
        }
        d.set<JsonNode>("recipes", keptRecipes)
        val ingredients = mapper.createArrayNode()
        d.path("recipeIngredients").forEach { node ->
            if (node.path("recipeId").asText() !in deleted) {
                convertAmount(node as ObjectNode)
                ingredients.add(node)
            }
        }
        d.set<JsonNode>("recipeIngredients", ingredients)
        d.path("weeklyMenus").forEach { (it as ObjectNode).put("revision", 1) }
        d.path("menuDays").forEach {
            (it as ObjectNode).put("requirementOrigin", "legacy").put("dayLocked", false).put("revision", 1)
        }
        d.path("menuItems").forEach { (it as ObjectNode).put("locked", false) }
        val days = d.withArray("menuDays")
        d.path("weeklyMenus").forEach { week ->
            val monday = LocalDate.parse(week.path("weekStart").asText())
            for (offset in 5L..6L) {
                val date = monday.plusDays(offset).toString()
                if (days.none { it.path("date").asText() == date }) {
                    days.addObject().put("id", UUID.randomUUID().toString())
                        .put("weeklyMenuId", week.path("id").asText()).put("date", date)
                        .put("noMeal", true).put("noMealReason", "").put("noMealMemo", "")
                        .put("requirementOrigin", "legacy").put("dayLocked", false).put("revision", 1)
                }
            }
        }
        d.path("shoppingLists").forEach { (it as ObjectNode).putNull("sourceFingerprint").put("needsRegeneration", true) }
        d.path("shoppingItems").forEach { node ->
            val item = node as ObjectNode
            item.put("hasUnknownAmount", item.path("amount").isNull)
            convertAmount(item)
            item.putNull("foodMasterId")
            item.put("aggregationKey", BackupGraphValidator.aggregationKey(null, item.path("normalizedName").asText(), item.path("unit").asText()))
            val names = item.path("recipeNames")
            val usages = item.putArray("recipeUsages")
            names.forEach { usages.addObject().putNull("recipeId").put("recipeNameSnapshot", it.asText()) }
            item.remove("recipeNames")
        }
        val aliases = d.putArray("userFoodAliases")
        val macroGroups = mapOf("staple" to "grain", "protein" to "soy", "vegetable" to "vegetable", "dairy" to "dairy", "fruit" to "fruit", "other" to "seasoning")
        d.path("userFoodMasters").forEach { node ->
            val food = node as ObjectNode
            food.path("aliases").forEach { alias ->
                aliases.addObject().put("id", UUID.randomUUID().toString()).put("foodMasterId", food.path("id").asText())
                    .put("alias", alias.asText()).put("normalizedAlias", TextNormalizer.normalize(alias.asText()))
                    .put("updatedAt", candidate.path("createdAt").asText())
            }
            val groups = food.path("foodGroups").map { macroGroups.getValue(it.asText()) }.distinct()
            food.putArray("foodGroups").also { array -> groups.forEach(array::add) }
            food.remove(listOf("aliases", "salesAmount", "salesUnit"))
            food.put("baseUnit", "").put("revision", 1)
        }
        listOf("planOutcomes", "postponedPlans", "drafts").forEach(d::putArray)
        candidate.put("schemaVersion", "2.0")
        warnings += "旧バックアップを2.0へ変換しました。手入力の最終調理日は破棄し、履歴から再計算します。買い物リストは再生成が必要です。"
        return candidate
    }

    private fun convertAmount(node: ObjectNode) {
        if (!node.path("amount").isNull) node.put("amount", node.path("amount").decimalValue().toPlainString())
    }

    private fun resolveMasterVersion(root: ObjectNode) {
        val sourceVersion = root.path("masterVersion").asText()
        if (sourceVersion == masterVersion) return
        val mapping = legacyMasterMappings[sourceVersion] ?: error("内蔵材料の版に対応するID変換表がありません: $sourceVersion")
        fun remap(node: JsonNode) {
            if (node.isObject) {
                val objectNode = node as ObjectNode
                objectNode.fields().asSequence().toList().forEach { (key, value) ->
                    if (key == "foodMasterId" && value.isTextual && value.asText().startsWith("builtin-")) {
                        objectNode.put(key, mapping[value.asText()] ?: error("内蔵材料IDを変換できません: ${value.asText()}"))
                    } else if (key == "entityId" && objectNode.path("entityType").asText() == "food" && value.asText().startsWith("builtin-")) {
                        objectNode.put(key, mapping[value.asText()] ?: error("内蔵材料IDを変換できません: ${value.asText()}"))
                    } else remap(value)
                }
            } else if (node.isArray) node.forEach(::remap)
        }
        remap(root.path("data"))
        root.path("data").path("shoppingItems").forEach { node ->
            if (node.has("aggregationKey") && node.path("foodMasterId").isTextual) {
                (node as ObjectNode).put("aggregationKey", BackupGraphValidator.aggregationKey(
                    node.path("foodMasterId").asText(), node.path("normalizedName").asText(), node.path("unit").asText(),
                ))
            }
        }
        root.put("masterVersion", masterVersion)
    }
}
