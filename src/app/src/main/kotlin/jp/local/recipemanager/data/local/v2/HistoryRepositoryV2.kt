package jp.local.recipemanager.data.local.v2

import androidx.room.withTransaction
import jp.local.recipemanager.domain.model.MenuRole
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Actual cooking is independent from saved menus and shopping quantities. */
class HistoryRepositoryV2(
    private val database: RecipeManagerDatabaseV2,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val dao get() = database.historyDao()

    suspend fun all(): List<HistoryDetailsV2> = database.withTransaction { dao.all() }

    suspend fun recordManual(cookedDate: LocalDate, recipeId: String, role: String? = null): HistoryItemEntityV2 = database.withTransaction {
        val recipe = recipe(recipeId)
        val actualRole = role ?: recipe.category
        validateRole(actualRole)
        rejectDuplicate(cookedDate, recipeId)
        val header = prepareHeader(cookedDate)
        val item = HistoryItemEntityV2(uuid(), header.id, recipeId, recipe.name, actualRole)
        dao.insertItem(item)
        dao.recalculateLastCooked(recipeId)
        item
    }

    /** Missing/null map values mean not cooked; this never overwrites already recorded results. */
    suspend fun recordCooking(
        menuDayId: String,
        expectedDayRevision: Long,
        actualByMenuItem: Map<String, String?>,
    ): List<PlanOutcomeEntityV2> = database.withTransaction {
        val day = currentDay(menuDayId, expectedDayRevision)
        require(!day.noMeal) { "不要日には予定の調理結果を登録できません" }
        val date = LocalDate.parse(day.date)
        val items = dao.menuItems(day.id)
        require(items.isNotEmpty()) { "献立が未設定です" }
        require(actualByMenuItem.keys.all { key -> items.any { it.id == key } }) { "別の日の料理が含まれています" }
        require(items.all { dao.outcome(it.id) == null }) { "結果が記録済みです。先に結果を訂正してください" }
        val actualIds = actualByMenuItem.values.filterNotNull()
        require(actualIds.distinct().size == actualIds.size) { "同じ日の同じ料理は1件だけ登録できます" }
        actualIds.forEach { recipe(it); rejectDuplicate(date, it) }
        val header = if (actualIds.isEmpty()) null else prepareHeader(date)
        val now = now()
        val outcomes = items.map { item ->
            val actualId = actualByMenuItem[item.id]
            val actual = actualId?.let { recipe(it) }
            val historyItem = actual?.let {
                HistoryItemEntityV2(uuid(), checkNotNull(header).id, it.id, it.name, it.category).also { row -> dao.insertItem(row) }
            }
            PlanOutcomeEntityV2(
                uuid(), item.id,
                when { actual == null -> "not_cooked"; actual.id == item.recipeId -> "cooked"; else -> "changed" },
                historyItem?.id, now,
            ).also { dao.insertOutcome(it) }
        }
        actualIds.forEach { dao.recalculateLastCooked(it) }
        bumpDays(setOf(day.id), now)
        outcomes
    }

    /** An explicit result correction detaches a result while retaining the actual cooking history. */
    suspend fun clearOutcome(menuItemId: String, expectedDayRevision: Long, confirmed: Boolean) = database.withTransaction {
        require(confirmed) { "結果の関連解除には確認が必要です" }
        val item = menuItem(menuItemId)
        currentDay(item.menuDayId, expectedDayRevision)
        val outcome = dao.outcome(item.id) ?: return@withTransaction
        require(outcome.state != "postponed") { "延期は延期取消から解除してください" }
        dao.deleteOutcome(item.id)
        bumpDays(setOf(item.menuDayId), now())
    }

    suspend fun editItem(
        itemId: String,
        expectedUpdatedAt: String,
        cookedDate: LocalDate,
        recipeId: String,
        role: String,
        confirmed: Boolean,
        detachLinkedOutcomes: Boolean = false,
    ): HistoryItemEntityV2 = database.withTransaction {
        require(confirmed) { "履歴の訂正には確認が必要です" }
        validateRole(role)
        val old = dao.item(itemId) ?: error("調理履歴が見つかりません")
        val oldHeader = currentHeader(old.historyId, expectedUpdatedAt)
        val actual = recipe(recipeId)
        if (oldHeader.cookedDate == cookedDate.toString() && old.recipeId == recipeId && old.role == role) return@withTransaction old
        rejectDuplicate(cookedDate, recipeId, itemId)
        val changedDays = detachHistoryOutcomes(itemId, detachLinkedOutcomes)
        val target = if (oldHeader.cookedDate == cookedDate.toString()) touchHeader(oldHeader) else prepareHeader(cookedDate)
        val updated = old.copy(historyId = target.id, recipeId = actual.id,
            recipeNameSnapshot = if (old.recipeId == actual.id) old.recipeNameSnapshot else actual.name, role = role)
        check(dao.updateItem(updated) == 1) { "履歴を更新できませんでした" }
        if (oldHeader.id != target.id) cleanEmptyHeader(oldHeader)
        setOf(old.recipeId, recipeId).forEach { dao.recalculateLastCooked(it) }
        bumpDays(changedDays, now())
        updated
    }

    suspend fun deleteItem(
        itemId: String,
        expectedUpdatedAt: String,
        confirmed: Boolean,
        detachLinkedOutcomes: Boolean = false,
    ) = database.withTransaction {
        require(confirmed) { "履歴の削除には確認が必要です" }
        val item = dao.item(itemId) ?: error("調理履歴が見つかりません")
        val header = currentHeader(item.historyId, expectedUpdatedAt)
        val changedDays = detachHistoryOutcomes(itemId, detachLinkedOutcomes)
        check(dao.deleteItem(itemId) == 1) { "履歴を削除できませんでした" }
        cleanEmptyHeader(header)
        dao.recalculateLastCooked(item.recipeId)
        bumpDays(changedDays, now())
    }

    suspend fun postpone(menuItemId: String, targetDate: LocalDate, expectedDayRevision: Long): PostponedPlanEntityV2 = database.withTransaction {
        val item = menuItem(menuItemId)
        val day = currentDay(item.menuDayId, expectedDayRevision)
        require(!day.noMeal) { "不要日の料理は延期できません" }
        require(dao.outcome(item.id) == null && dao.postponementsForItem(item.id).none { it.sourceMenuItemId == item.id }) {
            "料理の結果または延期が記録済みです"
        }
        val now = now()
        val postponed = PostponedPlanEntityV2(uuid(), item.id, item.recipeId, targetDate.toString(), "pending", null, now)
        dao.insertPostponement(postponed)
        dao.insertOutcome(PlanOutcomeEntityV2(uuid(), item.id, "postponed", null, now))
        bumpDays(setOf(day.id), now)
        postponed
    }

    suspend fun confirmPostponement(
        id: String,
        expectedUpdatedAt: String,
        expectedSourceDayRevision: Long,
        target: MenuDayInputV2,
        expectedTargetWeekRevision: Long?,
        expectedTargetDayRevision: Long?,
        confirmed: Boolean,
    ): PostponedPlanEntityV2 = database.withTransaction {
        require(confirmed) { "延期先の確定には確認が必要です" }
        val pending = currentPostponement(id, expectedUpdatedAt)
        require(pending.state == "pending" && pending.targetMenuItemId == null) { "延期先は確定済みです" }
        val source = menuItem(pending.sourceMenuItemId)
        val sourceDay = currentDay(source.menuDayId, expectedSourceDayRevision)
        check(dao.outcome(source.id)?.state == "postponed" && source.recipeId == pending.recipeId) { "延期元の状態が一致しません" }
        require(target.date.toString() == pending.targetDate && !target.noMeal && target.selections.count { it.recipeId == pending.recipeId } == 1) {
            "延期先の日付または料理が一致しません"
        }
        val oldTarget = dao.dayForDate(pending.targetDate)
        if (oldTarget != null) {
            val targetItems = dao.menuItems(oldTarget.id)
            require(!oldTarget.dayLocked && targetItems.none { it.locked }) { "先に延期先の固定を解除してください" }
            require(targetItems.all { dao.outcome(it.id) == null }) { "先に延期先の実績・結果の関連を訂正してください" }
            require(targetItems.all { dao.postponementsForItem(it.id).isEmpty() }) { "延期先に関連する別の延期を先に訂正してください" }
        }
        val savedDay = MenuRepositoryV2(database).saveDay(target, expectedTargetWeekRevision, expectedTargetDayRevision, confirmDetach = false)
        val targetItem = savedDay.items.single { it.recipeId == pending.recipeId }
        require(targetItem.id != source.id && dao.postponementsForItem(targetItem.id).isEmpty()) { "延期先の料理が延期元または別の延期と競合しています" }
        val updated = pending.copy(state = "confirmed", targetMenuItemId = targetItem.id, updatedAt = laterTimestamp(pending.updatedAt))
        check(dao.updatePostponement(updated) == 1) { "延期先を確定できませんでした" }
        val changedDays = buildSet {
            add(sourceDay.id)
            if (oldTarget?.revision == savedDay.day.revision) add(savedDay.day.id)
        }
        bumpDays(changedDays, updated.updatedAt)
        updated
    }

    /** A confirmed target remains an ordinary saved dish; actual histories are never removed. */
    suspend fun cancelPostponement(
        id: String,
        expectedUpdatedAt: String,
        expectedSourceDayRevision: Long,
        confirmed: Boolean,
    ) = database.withTransaction {
        require(confirmed) { "延期の取消には確認が必要です" }
        val postponed = currentPostponement(id, expectedUpdatedAt)
        val source = menuItem(postponed.sourceMenuItemId)
        currentDay(source.menuDayId, expectedSourceDayRevision)
        require(dao.outcome(source.id)?.state == "postponed") { "延期元の結果が変更されています" }
        val days = buildSet {
            add(source.menuDayId)
            postponed.targetMenuItemId?.let { add(menuItem(it).menuDayId) }
        }
        check(dao.deletePostponement(postponed.id) == 1) { "延期を取り消せませんでした" }
        dao.deleteOutcome(source.id)
        bumpDays(days, now())
    }

    private suspend fun detachHistoryOutcomes(itemId: String, confirmed: Boolean): Set<String> {
        val outcomes = dao.outcomesForHistory(itemId)
        require(outcomes.isEmpty() || confirmed) { "予定の結果との関連解除を確認してください" }
        return outcomes.map { outcome ->
            val item = menuItem(outcome.menuItemId)
            dao.deleteOutcome(item.id)
            item.menuDayId
        }.toSet()
    }

    private suspend fun bumpDays(ids: Set<String>, timestamp: String) {
        val days = ids.map { dao.day(it) ?: error("献立日が見つかりません") }
        val weeks = days.map { it.weeklyMenuId }.distinct().map { dao.week(it) ?: error("献立週が見つかりません") }
        require(days.all { it.revision < Long.MAX_VALUE } && weeks.all { it.revision < Long.MAX_VALUE }) { "revisionの上限に達しました" }
        days.forEach { check(dao.bumpDay(it.id) == 1) }
        weeks.forEach { check(dao.bumpWeek(it.id, timestamp) == 1) }
    }

    private suspend fun prepareHeader(date: LocalDate): CookingHistoryEntityV2 {
        val existing = dao.headerForDate(date.toString())
        if (existing != null) return touchHeader(existing)
        val now = now()
        return CookingHistoryEntityV2(uuid(), date.toString(), now, now).also { dao.insertHeader(it) }
    }

    private suspend fun touchHeader(header: CookingHistoryEntityV2) = header.copy(updatedAt = laterTimestamp(header.updatedAt)).also {
        check(dao.updateHeader(it) == 1) { "調理日の更新に失敗しました" }
    }

    private suspend fun cleanEmptyHeader(header: CookingHistoryEntityV2) {
        if (dao.items(header.id).isEmpty()) dao.deleteHeader(header.id) else touchHeader(header)
    }

    private suspend fun currentHeader(id: String, expectedUpdatedAt: String): CookingHistoryEntityV2 =
        (dao.header(id) ?: error("調理日が見つかりません")).also {
            require(it.updatedAt == expectedUpdatedAt) { "調理履歴が変更されています。再読込してください" }
        }

    private suspend fun currentPostponement(id: String, expectedUpdatedAt: String): PostponedPlanEntityV2 =
        (dao.postponement(id) ?: error("延期予定が見つかりません")).also {
            require(it.updatedAt == expectedUpdatedAt) { "延期予定が変更されています。再読込してください" }
        }

    private suspend fun currentDay(id: String, expectedRevision: Long): MenuDayEntityV2 =
        (dao.day(id) ?: error("献立日が見つかりません")).also {
            require(it.revision == expectedRevision) { "献立日が変更されています。再読込してください" }
        }

    private suspend fun menuItem(id: String) = dao.menuItem(id) ?: error("予定料理が見つかりません")
    private suspend fun recipe(id: String) = dao.recipe(id) ?: error("料理が見つかりません")
    private suspend fun rejectDuplicate(date: LocalDate, recipeId: String, excludedItemId: String = "") {
        require(dao.duplicateCount(date.toString(), recipeId, excludedItemId) == 0) { "同じ日の同じ料理は登録済みです" }
    }

    private fun validateRole(role: String) { require(role in MenuRole.entries.map { it.name }) { "料理の役割が不正です" } }
    private fun now() = Instant.now(clock).toString()
    private fun laterTimestamp(previous: String): String = Instant.now(clock).let { now ->
        val before = Instant.parse(previous)
        (if (now > before) now else before.plusNanos(1)).toString()
    }
    private fun uuid() = UUID.randomUUID().toString()
}
