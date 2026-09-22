package jp.local.recipemanager.feature.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.data.local.HistoryWithItems
import jp.local.recipemanager.data.local.MenuItemEntity
import jp.local.recipemanager.data.local.RecipeEntity
import jp.local.recipemanager.domain.model.MenuRole
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MenuViewModel(private val repository: MenuRepository) : ViewModel() {
    val weekStart = MutableStateFlow(MenuRepository.mondayOf(LocalDate.now()))
    val week = weekStart.flatMapLatest(repository::week).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val holidays = weekStart.flatMapLatest(repository::holidays).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recipes = repository.recipes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val histories = repository.histories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val noMealDays = repository.noMealDays().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val message = MutableStateFlow<String?>(null)
    val evaluation = MutableStateFlow<MenuEvaluation?>(null)
    val proposing = MutableStateFlow(false)

    fun moveWeek(days: Long) { weekStart.value = weekStart.value.plusDays(days) }
    fun saveMenu(date: LocalDate, selections: Map<MenuRole, RecipeEntity>) = enqueue("献立を保存し、再評価しました") { repository.saveMenu(date, selections); evaluation.value = repository.evaluateWeek(weekStart.value) }
    fun saveNoMeal(date: LocalDate, reason: String, memo: String) = enqueue("ご飯不要日を保存しました") { repository.saveNoMeal(date, reason, memo) }
    fun deleteNoMeal(date: LocalDate) = enqueue("ご飯不要日を解除しました") { repository.deleteNoMeal(date) }
    fun recordCooking(date: LocalDate, items: List<MenuItemEntity>, ids: Set<String>) = enqueue("調理履歴に記録しました") { repository.recordCooking(date, items, ids) }
    fun updateHistory(history: HistoryWithItems, ids: Set<String>) = enqueue("履歴を更新しました") { repository.updateHistory(history, ids) }
    fun deleteHistory(id: String) = enqueue("履歴を削除しました") { repository.deleteHistory(id) }
    suspend fun day(date: LocalDate) = repository.day(date)
    fun clearMessage() { message.value = null }

    fun proposeWeek() = viewModelScope.launch {
        proposing.value = true
        message.value = runCatching {
            val result = repository.proposeWeek(weekStart.value)
            evaluation.value = result.evaluation
            "献立を自動提案しました"
        }.getOrElse { it.message ?: "自動提案に失敗しました" }
        proposing.value = false
    }

    fun evaluateWeek() = viewModelScope.launch { evaluation.value = repository.evaluateWeek(weekStart.value) }

    private fun enqueue(success: String, block: suspend () -> Unit) = viewModelScope.launch {
        message.value = runCatching { block(); success }.getOrElse { it.message ?: "処理に失敗しました" }
    }

    companion object {
        fun factory(application: RecipeManagerApplication): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = MenuViewModel(MenuRepository(application.database)) as T
        }
    }
}
