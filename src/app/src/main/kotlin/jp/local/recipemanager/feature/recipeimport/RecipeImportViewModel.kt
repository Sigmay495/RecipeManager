package jp.local.recipemanager.feature.recipeimport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.feature.recipe.RecipeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecipeImportUiState(
    val loading: Boolean = false,
    val preview: RecipeImportPreview? = null,
    val fatalError: String? = null,
    val result: RecipeImportResult? = null,
)

class RecipeImportViewModel(
    private val parser: RecipeImportParser,
    private val service: RecipeImportService,
) : ViewModel() {
    private val _state = MutableStateFlow(RecipeImportUiState())
    val state: StateFlow<RecipeImportUiState> = _state.asStateFlow()

    fun load(fileName: String, bytes: ByteArray) = viewModelScope.launch {
        _state.value = RecipeImportUiState(loading = true)
        val parsed = withContext(Dispatchers.Default) { parser.parse(fileName, bytes) }
        _state.value = when (parsed) {
            is ImportLoadResult.Failure -> RecipeImportUiState(fatalError = parsed.message)
            is ImportLoadResult.Success -> RecipeImportUiState(preview = service.detectDuplicates(parsed.preview))
        }
    }

    fun fail(message: String) { _state.value = RecipeImportUiState(fatalError = message) }

    fun select(index: Int, action: ImportAction, restoreRecipeId: String? = null) {
        val preview = _state.value.preview ?: return
        _state.value = _state.value.copy(preview = preview.copy(candidates = preview.candidates.map { candidate ->
            if (candidate.index == index) candidate.copy(action = action, restoreRecipeId = restoreRecipeId) else candidate
        }))
    }

    fun execute() = viewModelScope.launch {
        val preview = _state.value.preview ?: return@launch
        _state.value = _state.value.copy(loading = true, fatalError = null)
        runCatching { service.import(preview) }
            .onSuccess { _state.value = RecipeImportUiState(result = it) }
            .onFailure { _state.value = _state.value.copy(loading = false, fatalError = it.message ?: "Importに失敗しました") }
    }

    fun reset() { _state.value = RecipeImportUiState() }

    companion object {
        fun factory(application: RecipeManagerApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val database = application.database
                val repository = RecipeRepository(database.recipeDao(), database.foodMasterDao())
                return RecipeImportViewModel(
                    RecipeImportParser(application),
                    RecipeImportService(database, repository, database.foodMasterDao()),
                ) as T
            }
        }
    }
}
