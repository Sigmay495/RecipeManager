package jp.local.recipemanager.data.local.v2

import androidx.room.withTransaction
import jp.local.recipemanager.core.json.JsonInput
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate

/** Preview ownership prevents callers from substituting unvalidated proposal data for adoption. */
class MenuProposalPreviewV2 internal constructor(
    val result: ProposalResultV2,
    internal val request: ProposalRequestV2,
    internal val fingerprint: String,
)

/** Produces previews without writes, then rechecks their complete source snapshot before adoption. */
class MenuProposalRepositoryV2(
    private val database: RecipeManagerDatabaseV2,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val engine = ProposalEngineV2()
    private val menus get() = MenuRepositoryV2(database)

    suspend fun preview(weekStart: LocalDate, seed: Long): MenuProposalPreviewV2 {
        val snapshot = database.withTransaction { snapshot(weekStart, seed) }
        return MenuProposalPreviewV2(engine.propose(snapshot.request), snapshot.request, snapshot.fingerprint)
    }

    internal suspend fun evaluationRequest(weekStart: LocalDate, seed: Long): ProposalRequestV2 =
        database.withTransaction { snapshot(weekStart, seed).request }

    suspend fun adopt(preview: MenuProposalPreviewV2): MenuWeekV2 = database.withTransaction {
        val selected = preview.result as? ProposalResultV2.Success ?: error("採用できる提案がありません")
        require(LocalDate.now(clock) == preview.request.today) { "日付が変わりました。提案を再確認してください" }
        val current = snapshot(preview.request.weekStart, preview.request.seed)
        require(current.fingerprint == preview.fingerprint) { "提案の生成元が変更されています。再提案してください" }
        engine.validate(current.request, selected.days, selected.relaxationLevel)
        selected.days.sortedBy { it.date }.forEach { proposed ->
            val week = menus.week(preview.request.weekStart)
            val old = week.days.single { it.day.date == proposed.date.toString() }
            val oldItems = old.items.map { ProposalItemV2(it.recipeId, it.role, it.locked) }.sortedBy { it.role }
            val unchanged = old.day.id.isNotEmpty() && old.day.noMeal == proposed.noMeal &&
                old.day.dayLocked == proposed.dayLocked && oldItems == proposed.items.sortedBy { it.role }
            if (!unchanged) {
                menus.saveDay(
                    MenuDayInputV2(
                        date = proposed.date, noMeal = proposed.noMeal,
                        selections = proposed.items.map { MenuSelectionV2(it.recipeId, it.role, it.locked) },
                        dayLocked = proposed.dayLocked, requirementOrigin = old.day.requirementOrigin,
                        noMealReason = old.day.noMealReason, noMealMemo = old.day.noMealMemo,
                    ),
                    expectedWeekRevision = week.menu?.revision,
                    expectedDayRevision = old.day.revision.takeIf { old.day.id.isNotEmpty() },
                )
            }
        }
        menus.week(preview.request.weekStart)
    }

    private data class Snapshot(val request: ProposalRequestV2, val fingerprint: String)

    private suspend fun snapshot(start: LocalDate, seed: Long): Snapshot {
        val week = menus.week(start)
        val source = database.proposalReadDao()
        val reference = database.referenceDao()
        val recipes = database.recipeDao().all().sortedBy { it.recipe.id }.map { it.copy(ingredients = it.ingredients.sortedBy { row -> row.displayOrder }) }
        val masters = database.foodDao().all().sortedBy { it.id }
        val groups = source.groups()
        val seasons = source.seasons()
        val aliases = source.userAliases()
        val builtIns = source.builtInAliases()
        val foods = masters.map { master ->
            FoodDetailsV2(master, aliases.filter { it.foodMasterId == master.id }, builtIns.filter { it.foodMasterId == master.id },
                groups.filter { it.foodMasterId == master.id }.map { it.foodGroup }.toSet(),
                seasons.filter { it.foodMasterId == master.id }.map { it.month }.toSet())
        }
        val allDays = reference.days().sortedBy { it.id }
        val allWeeks = reference.menus().sortedBy { it.id }
        val items = source.items()
        val outcomes = source.outcomes()
        val postponements = source.postponements()
        val histories = source.histories()
        val historyItems = source.historyItems()
        val today = LocalDate.now(clock)
        val recorded = outcomes.map { it.menuItemId }.toSet()
        val days = week.days.map { day ->
            val date = LocalDate.parse(day.day.date)
            ProposalDayV2(date, day.day.noMeal, day.day.dayLocked,
                day.items.map { ProposalItemV2(it.recipeId, it.role, it.locked) },
                protectedDay = date.isBefore(today) || day.items.any { it.id in recorded })
        }
        val dates = histories.associate { it.id to LocalDate.parse(it.cookedDate) }
        val historicRecipes = historyItems.map { dates.getValue(it.historyId) to it.recipeId }
        val dayById = allDays.associateBy { it.id }
        val weekById = allWeeks.associateBy { it.id }
        val otherPlans = items.mapNotNull { item ->
            val day = dayById.getValue(item.menuDayId)
            if (!day.noMeal && weekById.getValue(day.weeklyMenuId).weekStart != start.toString())
                LocalDate.parse(day.date) to item.recipeId else null
        }
        val request = ProposalRequestV2(start, today, days, recipes, foods, historicRecipes, otherPlans, seed)
        // Entities contain only strings/numbers/booleans. Encode arrays rather than delimiter-joined keys.
        val encoded = JsonInput.mapper().writeValueAsBytes(listOf(
            start.toString(), recipes, masters, groups, seasons, aliases, builtIns,
            allWeeks, allDays, items, outcomes, postponements, histories, historyItems,
            source.holidays(start.toString(), start.plusDays(6).toString()),
        ))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Snapshot(request, fingerprint)
    }
}
