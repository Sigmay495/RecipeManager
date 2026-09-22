package jp.local.recipemanager.feature.recipeimport

import jp.local.recipemanager.feature.recipe.RecipeDraft

enum class ImportAction { ADD, RESTORE, SKIP, ERROR }

data class ImportDuplicate(
    val recipeId: String,
    val name: String,
    val deleted: Boolean,
    val reason: String,
)

data class RecipeImportCandidate(
    val index: Int,
    val draft: RecipeDraft?,
    val sourceWarnings: List<String>,
    val errors: List<String>,
    val duplicates: List<ImportDuplicate> = emptyList(),
    val action: ImportAction = if (errors.isEmpty()) ImportAction.ADD else ImportAction.ERROR,
    val restoreRecipeId: String? = null,
) {
    val displayName: String get() = draft?.name ?: "${index + 1}件目"
}

data class RecipeImportPreview(
    val fileName: String,
    val candidates: List<RecipeImportCandidate>,
) {
    val validCount: Int get() = candidates.count { it.errors.isEmpty() }
    val errorCount: Int get() = candidates.count { it.errors.isNotEmpty() }
}

data class RecipeImportResult(val added: Int, val restored: Int, val skipped: Int, val errors: Int)

sealed interface ImportLoadResult {
    data class Success(val preview: RecipeImportPreview) : ImportLoadResult
    data class Failure(val message: String) : ImportLoadResult
}
