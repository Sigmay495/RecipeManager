package jp.local.recipemanager.data.local.v2

import androidx.room.withTransaction
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class MenuSelectionV2(val recipeId: String, val role: String, val locked: Boolean = false)

data class MenuDayInputV2(
    val date: LocalDate,
    val noMeal: Boolean,
    val selections: List<MenuSelectionV2> = emptyList(),
    val dayLocked: Boolean = false,
    val requirementOrigin: String = "explicit",
    val noMealReason: String? = null,
    val noMealMemo: String? = null,
)

data class MenuDayDetailsV2(val day: MenuDayEntityV2, val items: List<MenuItemEntityV2>)
data class MenuWeekV2(val menu: WeeklyMenuEntityV2?, val days: List<MenuDayDetailsV2>, val coveredHolidayYears: Set<Int> = emptySet())

/** Opaque and memory-only. The screen must discard it when its undo notification ends or navigation occurs. */
class NoMealUndoTokenV2 internal constructor(internal val owner: String, internal val id: String)
data class NoMealChangeV2(val day: MenuDayDetailsV2, val undoToken: NoMealUndoTokenV2?)

/** V2 manual menu writes. The production v1 database remains disconnected from this repository. */
class MenuRepositoryV2(private val database: RecipeManagerDatabaseV2, private val clock: Clock = Clock.systemDefaultZone()) {
    private val dao get() = database.menuDao()
    private val owner = UUID.randomUUID().toString()
    private val undo = mutableMapOf<String, UndoState>()

    suspend fun week(start: LocalDate): MenuWeekV2 = database.withTransaction { readWeek(monday(start)) }

    suspend fun saveDay(
        input: MenuDayInputV2,
        expectedWeekRevision: Long?,
        expectedDayRevision: Long?,
        confirmDetach: Boolean = false,
    ): MenuDayDetailsV2 = database.withTransaction {
        persist(input, expectedWeekRevision, expectedDayRevision, confirmDetach)
    }

    suspend fun setNoMealWithUndo(
        date: LocalDate,
        expectedWeekRevision: Long?,
        expectedDayRevision: Long?,
        confirmDetach: Boolean = false,
    ): NoMealChangeV2 = database.withTransaction {
        val old = readWeek(monday(date)).days.single { it.day.date == date.toString() }
        val watched = watch(old)
        val saved = persist(MenuDayInputV2(date, noMeal = true), expectedWeekRevision, expectedDayRevision, confirmDetach)
        val token = if (old.day.noMeal) null else NoMealUndoTokenV2(owner, UUID.randomUUID().toString()).also {
            undo[it.id] = UndoState(old, watched, fingerprint(watched, saved.day.id))
        }
        NoMealChangeV2(saved, token)
    }

    fun discardUndo(token: NoMealUndoTokenV2) {
        if (token.owner == owner) undo.remove(token.id)
    }

    fun discardAllUndo() = undo.clear()

    suspend fun undoNoMeal(token: NoMealUndoTokenV2): MenuDayDetailsV2 {
        require(token.owner == owner) { "取消情報はこの画面のものではありません" }
        val state = undo.remove(token.id) ?: error("取消できる期間は終了しました")
        return database.withTransaction {
            val date = LocalDate.parse(state.original.day.date)
            val current = dao.day(date.toString()) ?: error("取消対象が変更されています")
            require(fingerprint(state.watch, current.id) == state.after) { "関連する献立・実績・延期情報が変更されています。取消できません" }
            val week = dao.weekById(current.weeklyMenuId) ?: error("対象週が見つかりません")
            require(current.noMeal && dao.items(current.id).isEmpty()) { "取消対象の要否が変更されています" }
            val restored = state.original.day.copy(id = current.id, weeklyMenuId = week.id, revision = next(current.revision))
            val items = state.original.items.map { it.copy(menuDayId = current.id) }
            dao.updateDay(restored)
            if (items.isNotEmpty()) dao.insertItems(items)
            dao.updateWeek(week.copy(revision = next(week.revision), updatedAt = Instant.now(clock).toString()))
            dao.invalidateShopping(week.weekStart)
            // Detached results and postponements are intentionally not restored; histories remain untouched.
            MenuDayDetailsV2(restored, items.sortedWith(compareBy(MenuItemEntityV2::role, MenuItemEntityV2::id)))
        }
    }

    private suspend fun readWeek(start: LocalDate): MenuWeekV2 {
        val stored = dao.week(start.toString())
        val days = stored?.let { dao.days(it.id).associateBy(MenuDayEntityV2::date) }.orEmpty()
        val holidays = dao.holidays(start.toString(), start.plusDays(6).toString()).map { it.date }.toSet()
        return MenuWeekV2(stored, (0L..6L).map { offset ->
            val date = start.plusDays(offset)
            val day = days[date.toString()] ?: MenuDayEntityV2(
                "", stored?.id.orEmpty(), date.toString(), date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) || date.toString() in holidays,
                "", "", "default", false, 0,
            )
            MenuDayDetailsV2(day, if (day.id.isEmpty()) emptyList() else dao.items(day.id))
        }, dao.holidayYears().toSet())
    }

    private suspend fun persist(
        input: MenuDayInputV2,
        expectedWeekRevision: Long?,
        expectedDayRevision: Long?,
        confirmDetach: Boolean,
    ): MenuDayDetailsV2 {
        validateInput(input)
        val start = monday(input.date).toString()
        val oldWeek = dao.week(start)
        val oldDay = dao.day(input.date.toString())
        require(oldWeek?.revision == expectedWeekRevision && oldDay?.revision == expectedDayRevision) { "献立が別の操作で変更されています。再読込してください" }
        require(oldDay == null || oldDay.weeklyMenuId == oldWeek?.id) { "献立日の所属週が一致しません" }
        val oldItems = oldDay?.let { dao.items(it.id) }.orEmpty()
        val oldByPair = oldItems.associateBy { it.role to it.recipeId }
        val recipes = dao.recipes().associateBy { it.id }
        val pairs = input.selections.map { it.role to it.recipeId }.toSet()
        val compositionChanged = pairs != oldByPair.keys
        if (!input.noMeal && compositionChanged) {
            require(oldDay?.dayLocked != true && oldItems.filter { it.locked }.all { it.role to it.recipeId in pairs }) {
                "固定料理を変更する前に固定を解除してください"
            }
        }
        input.selections.forEach { selection ->
            val recipe = recipes[selection.recipeId] ?: error("料理が見つかりません")
            val existing = oldByPair[selection.role to selection.recipeId]
            require(recipe.proposalPriority != "retired" || existing != null) { "廃止した料理は新しく選択できません" }
            require((existing != null && !input.dayLocked && !selection.locked) || recipe.category == selection.role) {
                "料理のカテゴリと献立の役割が一致しません"
            }
        }
        val now = Instant.now(clock).toString()
        val weekId = oldWeek?.id ?: UUID.randomUUID().toString()
        val dayId = oldDay?.id ?: UUID.randomUUID().toString()
        val day = MenuDayEntityV2(dayId, weekId, input.date.toString(), input.noMeal,
            input.noMealReason ?: oldDay?.noMealReason.orEmpty(), input.noMealMemo ?: oldDay?.noMealMemo.orEmpty(),
            input.requirementOrigin, input.dayLocked, oldDay?.revision ?: 1L)
        val items = input.selections.map { selection ->
            val previous = oldByPair[selection.role to selection.recipeId]
            previous?.copy(locked = selection.locked) ?: MenuItemEntityV2(UUID.randomUUID().toString(), dayId,
                selection.recipeId, recipes.getValue(selection.recipeId).name, selection.role, selection.locked)
        }.sortedWith(compareBy(MenuItemEntityV2::role, MenuItemEntityV2::id))
        if (oldDay != null && oldDay == day && oldItems == items) return MenuDayDetailsV2(oldDay, oldItems)

        val removed = oldItems.filter { it.role to it.recipeId !in pairs }
        val otherChangedDays = detachForRemoval(removed, oldDay, confirmDetach)
        if (oldWeek == null) dao.insertWeek(WeeklyMenuEntityV2(weekId, start, now, now, 1))
        val savedDay = if (oldDay == null) day else day.copy(revision = next(oldDay.revision))
        if (oldDay == null) dao.insertDay(savedDay) else dao.updateDay(savedDay)
        if (removed.isNotEmpty()) dao.deleteItems(removed.map { it.id })
        val newItems = items.filter { it.id !in oldItems.map(MenuItemEntityV2::id).toSet() }
        val keptItems = items.filter { it.id in oldItems.map(MenuItemEntityV2::id).toSet() }
        if (newItems.isNotEmpty()) dao.insertItems(newItems)
        if (keptItems.isNotEmpty()) dao.updateItems(keptItems)
        val changedWeeks = mutableSetOf(weekId)
        otherChangedDays.filter { it != dayId }.forEach { id ->
            val related = dao.dayById(id) ?: error("関連する献立日が見つかりません")
            dao.updateDay(related.copy(revision = next(related.revision)))
            changedWeeks += related.weeklyMenuId
        }
        changedWeeks.forEach { id ->
            if (id != weekId || oldWeek != null) {
                val stored = dao.weekById(id) ?: error("関連する週が見つかりません")
                dao.updateWeek(stored.copy(revision = next(stored.revision), updatedAt = now))
            }
        }
        if (oldDay?.noMeal != input.noMeal || compositionChanged) dao.invalidateShopping(start)
        return MenuDayDetailsV2(savedDay, items)
    }

    private suspend fun detachForRemoval(removed: List<MenuItemEntityV2>, oldDay: MenuDayEntityV2?, confirmed: Boolean): Set<String> {
        if (removed.isEmpty()) return emptySet()
        val ids = removed.map { it.id }.toSet()
        val allItems = dao.allItems().associateBy { it.id }
        val outcomes = dao.outcomes()
        val postponements = dao.postponements().filter { it.sourceMenuItemId in ids || it.targetMenuItemId in ids }
        val targetsBeingRemoved = postponements.filter { it.state == "confirmed" && it.targetMenuItemId in ids }
        targetsBeingRemoved.forEach { postponed ->
            val target = allItems[postponed.targetMenuItemId] ?: error("確定した延期先が見つかりません")
            require(!target.locked && oldDay?.dayLocked != true) { "確定した延期先の固定を先に解除してください" }
            require(outcomes.none { it.menuItemId == target.id && it.historyItemId != null }) { "確定した延期先の実績関連を先に訂正してください" }
        }
        val detachedOutcomes = outcomes.filter { it.menuItemId in ids ||
            (it.state == "postponed" && postponements.any { p -> p.sourceMenuItemId == it.menuItemId }) }
        require(confirmed || (detachedOutcomes.isEmpty() && postponements.isEmpty())) { "結果の関連解除・延期取消の確認が必要です" }
        if (postponements.isNotEmpty()) dao.deletePostponements(postponements.map { it.id })
        if (detachedOutcomes.isNotEmpty()) dao.deleteOutcomes(detachedOutcomes.map { it.id })
        return detachedOutcomes.mapNotNull { allItems[it.menuItemId]?.menuDayId }.toSet()
    }

    private fun validateInput(input: MenuDayInputV2) {
        require(input.requirementOrigin in setOf("explicit", "default", "legacy")) { "要否の指定由来が不正です" }
        require((input.noMealReason?.length ?: 0) <= 200 && (input.noMealMemo?.length ?: 0) <= 200) { "理由・メモは200文字以内です" }
        val roles = input.selections.map { it.role }
        require(roles.all { it in setOf("ONE_DISH", "MAIN", "SIDE", "SOUP") } && roles.toSet().size == roles.size) { "献立の役割が不正または重複しています" }
        require(input.selections.map { it.recipeId }.toSet().size == input.selections.size) { "同日の料理が重複しています" }
        val complete = roles.toSet() == setOf("ONE_DISH") || roles.toSet() == setOf("MAIN", "SIDE", "SOUP")
        require(if (input.noMeal) input.selections.isEmpty() else input.selections.isEmpty() || complete) { "献立は一品ものか主菜・副菜・汁物の構成にしてください" }
        require((!input.dayLocked && input.selections.none { it.locked }) || (!input.noMeal && complete)) { "未設定・不要の日は固定できません" }
    }

    private data class Watch(val items: Set<String>, val days: Set<String>, val recipes: Set<String>)
    private data class Fingerprint(
        val weeks: List<WeeklyMenuEntityV2>, val days: List<MenuDayEntityV2>, val items: List<MenuItemEntityV2>,
        val outcomes: List<PlanOutcomeEntityV2>, val postponed: List<PostponedPlanEntityV2>,
        val histories: List<CookingHistoryEntityV2>, val historyItems: List<HistoryItemEntityV2>, val recipes: List<RecipeEntityV2>,
    )
    private data class UndoState(val original: MenuDayDetailsV2, val watch: Watch, val after: Fingerprint)

    private suspend fun watch(original: MenuDayDetailsV2): Watch {
        val related = relatedItems(original.items.map { it.id }.toSet())
        val items = dao.allItems().filter { it.id in related }
        return Watch(related, items.map { it.menuDayId }.toSet() + original.day.id,
            items.map { it.recipeId }.toSet() + original.items.map { it.recipeId })
    }

    private suspend fun relatedItems(seed: Set<String>): Set<String> {
        val ids = seed.toMutableSet()
        val postponements = dao.postponements()
        do {
            val size = ids.size
            postponements.filter { it.sourceMenuItemId in ids || it.targetMenuItemId in ids }.forEach {
                ids += it.sourceMenuItemId; it.targetMenuItemId?.let(ids::add)
            }
        } while (ids.size != size)
        return ids
    }

    private suspend fun fingerprint(watch: Watch, subjectDayId: String): Fingerprint {
        val ids = relatedItems(watch.items + dao.items(subjectDayId).map { it.id })
        val items = dao.allItems().filter { it.id in ids || it.menuDayId == subjectDayId }
        val dayIds = watch.days + items.map { it.menuDayId } + subjectDayId
        val days = dao.allDays().filter { it.id in dayIds }
        val weekIds = days.map { it.weeklyMenuId }.toSet()
        val recipeIds = watch.recipes + items.map { it.recipeId }
        val outcomes = dao.outcomes().filter { it.menuItemId in ids }
        val historyItemIds = outcomes.mapNotNull { it.historyItemId }.toSet()
        val historyItems = dao.historyItems().filter { it.recipeId in recipeIds || it.id in historyItemIds }
        val historyIds = historyItems.map { it.historyId }.toSet()
        return Fingerprint(dao.allWeeks().filter { it.id in weekIds }, days, items, outcomes,
            dao.postponements().filter { it.sourceMenuItemId in ids || it.targetMenuItemId in ids },
            dao.histories().filter { it.id in historyIds }, historyItems, dao.recipes().filter { it.id in recipeIds })
    }

    private fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun next(revision: Long): Long {
        require(revision in 1 until Long.MAX_VALUE) { "献立の版が範囲外です" }
        return revision + 1
    }
}
