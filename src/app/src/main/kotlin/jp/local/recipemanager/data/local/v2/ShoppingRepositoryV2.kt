package jp.local.recipemanager.data.local.v2

import androidx.room.withTransaction
import com.fasterxml.jackson.databind.JsonNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

data class ShoppingWeekV2(val data: ShoppingListDetailsV2?, val needsRegeneration: Boolean, val warnings: List<String>)
data class ShoppingGenerationV2(val data: ShoppingListDetailsV2, val warnings: List<String>)

/** Shopping is a snapshot of saved requirements; actual cooking does not remove purchased quantities. */
class ShoppingRepositoryV2(private val database: RecipeManagerDatabaseV2, private val clock: Clock = Clock.systemUTC()) {
    private val dao get() = database.shoppingDao()
    private val mapper = JsonInput.mapper()
    private data class Identity(val foodMasterId: String?, val normalizedName: String)
    private data class Contribution(val identity: Identity, val name: String, val normalizedName: String, val unit: String, val amount: BigDecimal?, val recipeId: String, val snapshot: String)
    private data class Source(val week: WeeklyMenuEntityV2?, val rows: List<Contribution>, val fingerprint: String, val warnings: List<String>, val foods: Map<String, FoodMasterEntityV2>, val names: Map<String, String>)

    suspend fun week(date: LocalDate): ShoppingWeekV2 = database.withTransaction {
        val start = monday(date)
        val source = source(start)
        val stored = dao.week(start.toString())?.ordered()
        ShoppingWeekV2(stored, stored != null && (stored.list.needsRegeneration || stored.list.sourceFingerprint != source.fingerprint), source.warnings)
    }

    suspend fun generate(date: LocalDate): ShoppingGenerationV2 = database.withTransaction {
        val start = monday(date)
        val source = source(start)
        val menu = source.week ?: error("この週の献立を先に保存してください")
        val old = dao.week(start.toString())
        val id = old?.list?.id ?: UUID.randomUUID().toString()
        val warnings = source.warnings.toMutableList()
        val oldByMaterial = old?.items.orEmpty().groupBy { item ->
            require(item.amount != null || item.hasUnknownAmount) { "保存済みの数量不明情報が不正です" }
            item.amount?.let(::amount)
            identity(item.foodMasterId, item.normalizedName, source.foods, source.names)
        }
        val rows = source.rows.groupBy { it.identity to it.unit }.map { (key, contributions) ->
            val (material, unit) = key
            val sameMaterial = oldByMaterial[material].orEmpty()
            val sameUnit = sameMaterial.filter { it.unit.trim() == unit }
            val previous = sameUnit.singleOrNull()
            val known = contributions.mapNotNull { it.amount }
            val subtotal = if (known.isEmpty()) null else known.fold(BigDecimal.ZERO, BigDecimal::add)
            val unknown = contributions.any { it.amount == null }
            val checked = previous?.checked == true && !previous.hasUnknownAmount && !unknown &&
                subtotal != null && previous.amount?.let(::amount)?.let { subtotal <= it } == true
            val exclusionSources = sameUnit.ifEmpty { sameMaterial }
            val excluded = exclusionSources.isNotEmpty() && exclusionSources.all { it.excluded }
            val display = contributions.first()
            if (!checked && sameUnit.ifEmpty { sameMaterial }.any { it.checked }) {
                val reason = when {
                    sameUnit.isEmpty() -> "単位が変わったため購入済みを解除しました"
                    sameUnit.size > 1 -> "材料の対応を再確認するため購入済みを解除しました"
                    previous?.hasUnknownAmount == true || unknown -> "数量不明を含むため購入済みを解除しました"
                    else -> "必要量が増えたため購入済みを解除しました"
                }
                warnings += "${display.name}: $reason"
            }
            if (exclusionSources.map { it.excluded }.distinct().size > 1) warnings += "${display.name}: 購入不要の状態が混在していたため再確認してください"
            val usages = linkedSetOf<JsonNode>()
            contributions.sortedWith(compareBy(Contribution::recipeId, Contribution::snapshot)).forEach { contribution ->
                usages.add(mapper.createObjectNode().put("recipeId", contribution.recipeId).put("recipeNameSnapshot", contribution.snapshot))
            }
            ShoppingItemEntityV2(previous?.id ?: UUID.randomUUID().toString(), id, display.name, display.normalizedName,
                subtotal?.toPlainString(), unit, mapper.createArrayNode().addAll(usages.toList()).toString(), checked, excluded,
                material.foodMasterId, aggregationKey(material, unit), unknown)
        }.sortedWith(compareBy(ShoppingItemEntityV2::normalizedName, ShoppingItemEntityV2::unit, ShoppingItemEntityV2::id))
        val list = ShoppingListEntityV2(id, start.toString(), menu.updatedAt, old?.list?.createdAt ?: Instant.now(clock).toString(), source.fingerprint, false)
        if (old == null) dao.insertList(list) else dao.updateList(list)
        dao.clearItems(id)
        if (rows.isNotEmpty()) dao.insertItems(rows)
        ShoppingGenerationV2(ShoppingListDetailsV2(list, rows), warnings.distinct())
    }

    suspend fun updateState(expected: ShoppingItemEntityV2, checked: Boolean = expected.checked, excluded: Boolean = expected.excluded): ShoppingItemEntityV2 = database.withTransaction {
        val current = dao.item(expected.id) ?: error("買い物項目が変更または削除されています")
        require(current == expected) { "買い物項目が別の操作で変更されています。再読込してください" }
        current.copy(checked = checked, excluded = excluded).also { dao.updateItem(it) }
    }

    private suspend fun source(start: LocalDate): Source {
        val week = MenuRepositoryV2(database).week(start)
        val recipes = database.recipeDao().all().associateBy { it.recipe.id }
        val foods = database.foodDao().all().associateBy { it.id }
        val names = database.foodDao().names().groupBy { it.normalizedName }.mapValues { (_, rows) ->
            val ids = rows.map { it.foodMasterId }.distinct()
            check(ids.size == 1) { "材料名・別名が競合しています" }
            ids.single()
        }
        val contributions = mutableListOf<Contribution>()
        val warnings = mutableListOf<String>()
        val sourceDays = week.days.map { day ->
            if (!day.day.noMeal && day.items.isEmpty()) warnings += "${day.day.date}: 献立未設定のため買い物に含めていません"
            val dishes = if (day.day.noMeal) emptyList() else day.items.sortedWith(compareBy(MenuItemEntityV2::role, MenuItemEntityV2::recipeId)).map { item ->
                val recipe = recipes[item.recipeId] ?: error("献立の料理が見つかりません")
                val ingredients = recipe.ingredients.sortedBy { it.displayOrder }.map { ingredient ->
                    require(ingredient.normalizedName == TextNormalizer.normalize(ingredient.name) && ingredient.normalizedName.isNotBlank()) { "材料名の正規化値が不正です" }
                    val material = identity(ingredient.foodMasterId, ingredient.normalizedName, foods, names)
                    val master = material.foodMasterId?.let(foods::getValue)
                    val normalized = master?.normalizedName ?: ingredient.normalizedName
                    val display = master?.name ?: ingredient.name
                    val unit = ingredient.unit.trim()
                    val quantity = ingredient.amount?.let(::amount)
                    contributions += Contribution(material, display, normalized, unit, quantity, item.recipeId, item.recipeNameSnapshot)
                    listOf(ingredient.name, ingredient.normalizedName, display, ingredient.amount, unit, ingredient.note, material.foodMasterId, material.normalizedName, master?.revision)
                }
                listOf(item.role, item.recipeId, item.recipeNameSnapshot, ingredients)
            }
            listOf(day.day.date, day.day.noMeal, dishes)
        }
        // Never include day/weekly revisions or result records: outcome-only edits must not stale this snapshot.
        val bytes = mapper.writeValueAsBytes(listOf(start.toString(), sourceDays))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        return Source(week.menu, contributions, fingerprint, warnings, foods, names)
    }

    private fun identity(id: String?, normalized: String, foods: Map<String, FoodMasterEntityV2>, names: Map<String, String>): Identity {
        val resolved = id ?: names[normalized]
        require(resolved == null || resolved in foods) { "材料マスタへの参照が切れています" }
        return if (resolved == null) Identity(null, normalized) else Identity(resolved, "")
    }
    private fun aggregationKey(identity: Identity, unit: String): String = mapper.createArrayNode()
        .add(if (identity.foodMasterId == null) "name" else "food").add(identity.foodMasterId ?: identity.normalizedName).add(unit).toString()
    private fun amount(value: String): BigDecimal = value.toBigDecimal().also { require(it > BigDecimal.ZERO) { "分量は正数または不明である必要があります" } }
    private fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private fun ShoppingListDetailsV2.ordered() = copy(items = items.sortedWith(compareBy(ShoppingItemEntityV2::normalizedName, ShoppingItemEntityV2::unit, ShoppingItemEntityV2::id)))
}
