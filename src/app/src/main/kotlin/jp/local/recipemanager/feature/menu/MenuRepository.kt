package jp.local.recipemanager.feature.menu

import jp.local.recipemanager.data.local.*
import jp.local.recipemanager.domain.model.MenuRole
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class MenuRepository(private val database: RecipeManagerDatabase) {
    private val menuDao = database.menuDao()
    private val historyDao = database.historyDao()

    fun week(start: LocalDate): Flow<WeeklyMenuWithDays?> = menuDao.observeWeek(mondayOf(start))
    fun holidays(start: LocalDate) = database.systemDataDao().observeHolidays(mondayOf(start), mondayOf(start).plusDays(4))
    fun recipes() = database.recipeDao().observeActive()
    fun histories() = historyDao.observeAll()
    fun noMealDays() = menuDao.observeNoMealDays()

    suspend fun saveMenu(date: LocalDate, selections: Map<MenuRole, RecipeEntity>) {
        require(date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
        val weekStart = mondayOf(date)
        val existingWeek = menuDao.findByWeek(weekStart)
        val now = Instant.now()
        val menu = existingWeek?.menu?.copy(updatedAt = now) ?: WeeklyMenuEntity(UUID.randomUUID().toString(), weekStart, now, now)
        val oldDay = existingWeek?.days?.firstOrNull { it.day.date == date }?.day
        val day = MenuDayEntity(oldDay?.id ?: UUID.randomUUID().toString(), menu.id, date, false, "", "")
        val items = selections.map { (role, recipe) -> MenuItemEntity(UUID.randomUUID().toString(), day.id, recipe.id, recipe.name, role) }
        menuDao.saveDay(menu, day, items)
    }

    suspend fun saveNoMeal(date: LocalDate, reason: String, memo: String) {
        require(date.dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) { "土日はご飯不要日の対象外です" }
        require(memo.length <= 200) { "メモは200文字以内で入力してください" }
        val weekStart = mondayOf(date)
        val existingWeek = menuDao.findByWeek(weekStart)
        val now = Instant.now()
        val menu = existingWeek?.menu?.copy(updatedAt = now) ?: WeeklyMenuEntity(UUID.randomUUID().toString(), weekStart, now, now)
        val oldDay = existingWeek?.days?.firstOrNull { it.day.date == date }?.day
        menuDao.saveDay(menu, MenuDayEntity(oldDay?.id ?: UUID.randomUUID().toString(), menu.id, date, true, reason.trim(), memo.trim()), emptyList())
    }

    suspend fun deleteNoMeal(date: LocalDate) = menuDao.deleteNoMealDay(date)
    suspend fun day(date: LocalDate) = menuDao.findDay(date)

    suspend fun recordCooking(date: LocalDate, menuItems: List<MenuItemEntity>, includedIds: Set<String>) {
        val chosen = menuItems.filter { it.id in includedIds }
        require(chosen.isNotEmpty()) { "作った料理を1件以上選択してください" }
        val now = Instant.now()
        val old = historyDao.findById(historyId(date))
        val history = CookingHistoryEntity(historyId(date), date, old?.history?.createdAt ?: now, now)
        historyDao.save(history, chosen.map { HistoryItemEntity(UUID.randomUUID().toString(), history.id, it.recipeId, it.recipeNameSnapshot, it.role) })
    }

    suspend fun updateHistory(record: HistoryWithItems, includedIds: Set<String>) {
        val chosen = record.items.filter { it.id in includedIds }
        if (chosen.isEmpty()) historyDao.delete(record.history.id, Instant.now())
        else historyDao.save(record.history.copy(updatedAt = Instant.now()), chosen.map { it.copy(id = UUID.randomUUID().toString()) })
    }

    suspend fun deleteHistory(id: String) = historyDao.delete(id, Instant.now())

    suspend fun proposeWeek(start: LocalDate): MenuProposal {
        val monday = mondayOf(start)
        val recipes = database.recipeDao().getAll().filter { it.recipe.status.name == "ACTIVE" }
        val masters = database.foodMasterDao().getAll()
        val histories = historyDao.getAll()
        val historyCounts = histories.flatMap { it.items }.groupingBy { it.recipeId }.eachCount()
        val holidays = database.systemDataDao().holidaysBetween(monday, monday.plusDays(4)).map { it.date }.toSet()
        val noMeals = menuDao.findByWeek(monday)?.days.orEmpty().filter { it.day.noMeal }.map { it.day.date }.toSet()
        val dates = (0L..4L).map(monday::plusDays).filterNot { it in holidays || it in noMeals }
        val proposal = MenuProposalEngine(masters, historyCounts).propose(dates, recipes, Instant.now().toEpochMilli() xor monday.toEpochDay())
            ?: error("献立構成に必要なレシピが不足しています。一品もの、または主菜・副菜・汁物を登録してください")
        val existing = menuDao.findByWeek(monday)
        val now = Instant.now()
        val menu = existing?.menu?.copy(updatedAt = now) ?: WeeklyMenuEntity(UUID.randomUUID().toString(), monday, now, now)
        val dayEntities = proposal.days.map { proposed ->
            MenuDayEntity(existing?.days?.firstOrNull { it.day.date == proposed.date }?.day?.id ?: UUID.randomUUID().toString(), menu.id, proposed.date, false, "", "")
        }
        val items = proposal.days.flatMapIndexed { index, proposed -> proposed.dishes.map { dish ->
            MenuItemEntity(UUID.randomUUID().toString(), dayEntities[index].id, dish.recipe.recipe.id, dish.recipe.recipe.name, dish.role)
        } }
        menuDao.saveDays(menu, dayEntities, items)
        return proposal
    }

    suspend fun evaluateWeek(start: LocalDate): MenuEvaluation? {
        val monday = mondayOf(start)
        val week = menuDao.findByWeek(monday) ?: return null
        val recipes = database.recipeDao().getAll().associateBy { it.recipe.id }
        val histories = historyDao.getAll()
        val masters = database.foodMasterDao().getAll()
        val days = week.days.filter { !it.day.noMeal && it.items.isNotEmpty() }.sortedBy { it.day.date }.map { stored ->
            ProposedDay(stored.day.date, stored.items.mapNotNull { item -> recipes[item.recipeId]?.let { ProposedDish(item.role, it) } })
        }
        return MenuProposalEngine(masters, histories.flatMap { it.items }.groupingBy { it.recipeId }.eachCount()).evaluate(days)
    }

    companion object {
        fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        private fun historyId(date: LocalDate) = "history-$date"
    }
}
