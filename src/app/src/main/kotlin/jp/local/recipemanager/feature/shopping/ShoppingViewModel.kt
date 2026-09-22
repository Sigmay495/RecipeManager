package jp.local.recipemanager.feature.shopping

import androidx.lifecycle.*
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.data.local.ShoppingItemEntity
import jp.local.recipemanager.feature.menu.MenuRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ShoppingViewModel(private val repository: ShoppingRepository) : ViewModel() {
    val weekStart = MutableStateFlow(MenuRepository.mondayOf(LocalDate.now()))
    val list = weekStart.flatMapLatest(repository::observe).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val menuUpdatedAt = MutableStateFlow<java.time.Instant?>(null)
    val message = MutableStateFlow<String?>(null)
    fun moveWeek(days: Long) { weekStart.value = weekStart.value.plusDays(days); refresh() }
    fun refresh() = viewModelScope.launch { menuUpdatedAt.value = repository.menuUpdatedAt(weekStart.value) }
    fun generate() = viewModelScope.launch { message.value = runCatching { repository.generate(weekStart.value); refresh(); "買い物リストを生成しました" }.getOrElse { it.message ?: "生成に失敗しました" } }
    fun toggleChecked(item: ShoppingItemEntity) = viewModelScope.launch { repository.update(item.copy(checked = !item.checked)) }
    fun toggleExcluded(item: ShoppingItemEntity) = viewModelScope.launch { repository.update(item.copy(excluded = !item.excluded)) }
    companion object { fun factory(app: RecipeManagerApplication) = object : ViewModelProvider.Factory { @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ShoppingViewModel(ShoppingRepository(app.database)) as T } }
}
