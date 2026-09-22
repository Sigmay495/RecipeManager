package jp.local.recipemanager.feature.recipeimport

import android.content.Context
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.SchemaRegistry
import com.networknt.schema.SchemaRegistryConfig
import com.networknt.schema.dialect.Dialects
import jp.local.recipemanager.domain.model.EffortLevel
import jp.local.recipemanager.domain.model.RecipeCategory
import jp.local.recipemanager.feature.recipe.IngredientDraft
import jp.local.recipemanager.feature.recipe.RecipeDraft
import java.time.Instant

class RecipeImportParser(context: Context) {
    private val mapper = ObjectMapper()
    private val recipeSchema = run {
        val schemaRoot = context.assets.open(SCHEMA_ASSET).use(mapper::readTree)
        val wrapper = mapper.createObjectNode().apply {
            put("\$schema", "https://json-schema.org/draft/2020-12/schema")
            set<JsonNode>("\$defs", schemaRoot.path("\$defs"))
            put("\$ref", "#/\$defs/recipe")
        }
        val config = SchemaRegistryConfig.builder().formatAssertionsEnabled(true).build()
        val registry = SchemaRegistry.withDialect(Dialects.getDraft202012()) { it.schemaRegistryConfig(config) }
        registry.getSchema(wrapper)
    }

    fun parse(fileName: String, bytes: ByteArray): ImportLoadResult {
        if (bytes.size > MAX_FILE_BYTES) return ImportLoadResult.Failure("ファイルサイズは5MB以内にしてください")
        val root = try { mapper.readTree(bytes) } catch (_: Exception) {
            return ImportLoadResult.Failure("JSONの構文が正しくありません")
        }
        outerError(root)?.let { return ImportLoadResult.Failure(it) }
        val candidates = root.path("recipes").mapIndexed { index, node ->
            val errors = recipeSchema.validate(node).map { message ->
                val location = message.instanceLocation.toString().ifBlank { "/" }
                "$location: ${message.message}"
            }.sorted()
            if (errors.isNotEmpty()) {
                RecipeImportCandidate(index, null, emptyList(), errors)
            } else {
                RecipeImportCandidate(index, node.toDraft(), node.path("warnings").map(JsonNode::asText), emptyList())
            }
        }
        return ImportLoadResult.Success(RecipeImportPreview(fileName, candidates))
    }

    private fun outerError(root: JsonNode): String? {
        if (!root.isObject) return "JSONの最上位はオブジェクトである必要があります"
        val allowed = setOf("schemaVersion", "fileType", "generatedAt", "recipes")
        val unknown = root.fieldNames().asSequence().filterNot(allowed::contains).toList()
        if (unknown.isNotEmpty()) return "未対応の項目があります: ${unknown.joinToString()}"
        if (root.path("schemaVersion").asText() != "1.0") return "schemaVersionは1.0である必要があります"
        if (root.path("fileType").asText() != "recipe-import") return "fileTypeはrecipe-importである必要があります"
        val generatedAt = root.get("generatedAt")
        if (generatedAt != null && !generatedAt.isNull && (!generatedAt.isTextual || runCatching { Instant.parse(generatedAt.asText()) }.isFailure)) {
            return "generatedAtはISO 8601の日時である必要があります"
        }
        val recipes = root.get("recipes") ?: return "recipesがありません"
        if (!recipes.isArray) return "recipesは配列である必要があります"
        if (recipes.size() !in 1..100) return "recipesは1～100件にしてください"
        return null
    }

    private fun JsonNode.toDraft() = RecipeDraft(
        name = path("name").asText().trim(),
        category = when (path("category").asText()) {
            "one_dish" -> RecipeCategory.ONE_DISH
            "main" -> RecipeCategory.MAIN
            "side" -> RecipeCategory.SIDE
            else -> RecipeCategory.SOUP
        },
        ingredients = path("ingredients").map { ingredient ->
            IngredientDraft(
                name = ingredient.path("name").asText().trim(),
                amount = ingredient.get("amount")?.takeUnless(JsonNode::isNull)?.decimalValue()?.toPlainString().orEmpty(),
                unit = ingredient.path("unit").asText(),
                note = ingredient.path("note").asText(""),
            )
        },
        cookingTime = get("cookingTimeMinutes")?.takeUnless(JsonNode::isNull)?.asInt()?.toString().orEmpty(),
        effortLevel = when (path("effortLevel").asText("normal")) {
            "easy" -> EffortLevel.EASY
            "hard" -> EffortLevel.HARD
            else -> EffortLevel.NORMAL
        },
        sourceUrl = get("sourceUrl")?.takeUnless(JsonNode::isNull)?.asText().orEmpty(),
        memo = path("memo").asText(""),
    )

    companion object {
        const val MAX_FILE_BYTES = 5 * 1024 * 1024
        const val SCHEMA_ASSET = "recipe-import.schema.json"
    }
}
