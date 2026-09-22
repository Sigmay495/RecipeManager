package jp.local.recipemanager.feature.recipeimport

import androidx.room.withTransaction
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.data.local.FoodMasterDao
import jp.local.recipemanager.data.local.FoodMasterEntity
import jp.local.recipemanager.data.local.FoodMasterWithDetails
import jp.local.recipemanager.data.local.RecipeManagerDatabase
import jp.local.recipemanager.domain.model.RecipeStatus
import jp.local.recipemanager.feature.recipe.RecipeRepository
import java.net.URI
import java.util.UUID

class RecipeImportService(
    private val database: RecipeManagerDatabase,
    private val recipeRepository: RecipeRepository,
    private val foodMasterDao: FoodMasterDao,
) {
    suspend fun detectDuplicates(preview: RecipeImportPreview): RecipeImportPreview {
        val existing = database.recipeDao().getAll()
        val masters = foodMasterDao.getAll()
        val mainMasterIds = masters.filter { it.master.isMainIngredient }.mapTo(hashSetOf()) { it.master.id }
        val masterIdByName = buildMap {
            masters.forEach { details ->
                put(details.master.normalizedName, details.master.id)
                details.aliases.forEach { put(it.normalizedAlias, details.master.id) }
            }
        }
        return preview.copy(candidates = preview.candidates.map { candidate ->
            val draft = candidate.draft ?: return@map candidate
            val importedName = TextNormalizer.normalize(draft.name)
            val importedUrl = normalizeUrl(draft.sourceUrl)
            val importedMain = draft.ingredients.mapNotNull { masterIdByName[TextNormalizer.normalize(it.name)] }
                .filterTo(hashSetOf(), mainMasterIds::contains)
            val unmatched = draft.ingredients.map { it.name.trim() }
                .filter { TextNormalizer.normalize(it) !in masterIdByName }
                .distinct()
            val duplicates = existing.mapNotNull { record ->
                val sameName = record.recipe.normalizedName == importedName
                val sameUrl = importedUrl != null && importedUrl == normalizeUrl(record.recipe.sourceUrl.orEmpty())
                val existingMain = record.ingredients.mapNotNullTo(hashSetOf()) { it.foodMasterId?.takeIf(mainMasterIds::contains) }
                val overlap = if (importedMain.isEmpty()) 0.0 else importedMain.intersect(existingMain).size.toDouble() / importedMain.size
                val reason = when {
                    sameName && sameUrl -> "料理名とURLが一致"
                    sameUrl -> "URLが一致"
                    sameName && overlap >= 0.5 -> "料理名が一致し、主要材料が${(overlap * 100).toInt()}%一致"
                    else -> null
                } ?: return@mapNotNull null
                ImportDuplicate(record.recipe.id, record.recipe.name, record.recipe.status == RecipeStatus.DELETED, reason)
            }
            val deleted = duplicates.firstOrNull(ImportDuplicate::deleted)
            candidate.copy(
                sourceWarnings = candidate.sourceWarnings + unmatched.takeIf { it.isNotEmpty() }?.let {
                    "未登録食材候補: ${it.joinToString()}（利用者編集可能マスターとして追加します）"
                }.orEmpty(),
                duplicates = duplicates,
                action = when {
                    duplicates.isEmpty() -> ImportAction.ADD
                    deleted != null -> ImportAction.RESTORE
                    else -> ImportAction.SKIP
                },
                restoreRecipeId = deleted?.recipeId,
            )
        })
    }

    suspend fun import(preview: RecipeImportPreview): RecipeImportResult {
        var added = 0
        var restored = 0
        var skipped = 0
        database.withTransaction {
            preview.candidates.forEach { candidate ->
                when (candidate.action) {
                    ImportAction.ADD -> {
                        val draft = requireNotNull(candidate.draft)
                        ensureFoodMasters(draft)
                        recipeRepository.save(draft)
                        added++
                    }
                    ImportAction.RESTORE -> { recipeRepository.restore(requireNotNull(candidate.restoreRecipeId)); restored++ }
                    ImportAction.SKIP -> skipped++
                    ImportAction.ERROR -> Unit
                }
            }
        }
        return RecipeImportResult(added, restored, skipped, preview.errorCount)
    }

    private suspend fun ensureFoodMasters(draft: jp.local.recipemanager.feature.recipe.RecipeDraft) {
        draft.ingredients.forEach { ingredient ->
            val normalized = TextNormalizer.normalize(ingredient.name)
            if (foodMasterDao.findIdByNormalizedName(normalized) == null) {
                val id = UUID.randomUUID().toString()
                foodMasterDao.insert(
                    FoodMasterWithDetails(
                        master = FoodMasterEntity(
                            id = id,
                            name = ingredient.name.trim(),
                            normalizedName = normalized,
                            salesUnit = ingredient.unit.trim(),
                            isMainIngredient = true,
                            userEditable = true,
                        ),
                        aliases = emptyList(),
                        groups = emptyList(),
                        seasons = emptyList(),
                    ),
                )
            }
        }
    }

    private fun normalizeUrl(value: String): String? = value.trim().takeIf(String::isNotEmpty)?.let { raw ->
        runCatching {
            val uri = URI(raw).normalize()
            URI(uri.scheme.lowercase(), uri.userInfo, uri.host?.lowercase(), uri.port, uri.path, uri.query, uri.fragment).toASCIIString()
        }.getOrNull()
    }
}
