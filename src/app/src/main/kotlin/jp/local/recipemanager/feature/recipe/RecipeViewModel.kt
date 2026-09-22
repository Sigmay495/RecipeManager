package jp.local.recipemanager.feature.recipe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.data.local.RecipeWithIngredients
import jp.local.recipemanager.domain.model.RecipeStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecipeViewModel(private val repository: RecipeRepository) : ViewModel() {
    val filters = MutableStateFlow(RecipeFilters())
    val recipes: StateFlow<List<RecipeWithIngredients>> = combine(repository.recipes, filters) { items, filters ->
        val query = TextNormalizer.normalize(filters.query)
        items.asSequence()
            .filter { it.recipe.status == filters.status }
            .filter { filters.category == null || it.recipe.category == filters.category }
            .filter { filters.effortLevel == null || it.recipe.effortLevel == filters.effortLevel }
            .filter { filters.maximumMinutes == null || (it.recipe.cookingTimeMinutes ?: Int.MAX_VALUE) <= filters.maximumMinutes }
            .filter { record ->
                query.isEmpty() || record.recipe.normalizedName.contains(query) ||
                    record.ingredients.any { it.normalizedName.contains(query) }
            }
            .let { sequence ->
                when (filters.sort) {
                    RecipeSort.NAME -> sequence.sortedBy { it.recipe.normalizedName }
                    RecipeSort.UPDATED_AT -> sequence.sortedByDescending { it.recipe.updatedAt }
                    RecipeSort.LAST_COOKED_AT -> sequence.sortedWith(compareByDescending<RecipeWithIngredients> { it.recipe.lastCookedAt }.thenBy { it.recipe.normalizedName })
                }
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateFilters(transform: (RecipeFilters) -> RecipeFilters) {
        filters.value = transform(filters.value)
    }

    fun observeRecipe(id: String) = repository.observeRecipe(id)
    suspend fun loadDraft(id: String): RecipeDraft? = repository.draft(id)
    suspend fun validate(draft: RecipeDraft): RecipeValidation = repository.validate(draft)
    suspend fun save(draft: RecipeDraft): String = repository.save(draft)
    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }
    fun restore(id: String) = viewModelScope.launch { repository.restore(id) }

    companion object {
        fun factory(application: RecipeManagerApplication): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    RecipeViewModel(RecipeRepository(application.database.recipeDao(), application.database.foodMasterDao())) as T
            }
    }
}
