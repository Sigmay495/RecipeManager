package jp.local.recipemanager.data.local.v2

import androidx.room.withTransaction
import com.fasterxml.jackson.databind.JsonNode
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.domain.model.FoodGroup
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class FoodInputV2(
    val name: String,
    val aliases: List<String> = emptyList(),
    val foodGroups: Set<String> = emptySet(),
    val baseUnit: String = "",
    val seasonMonths: Set<Int> = emptySet(),
    val isMainIngredient: Boolean = false,
    val id: String? = null,
    val expectedRevision: Long? = null,
)

data class FoodDetailsV2(
    val master: FoodMasterEntityV2,
    val aliases: List<UserFoodAliasEntityV2>,
    val builtInAliases: List<FoodMasterAliasEntityV2>,
    val foodGroups: Set<String>,
    val seasonMonths: Set<Int>,
)

data class FoodMergeResultV2(
    val target: FoodMasterEntityV2,
    val changedRecipeIds: Set<String>,
    val mixedExcludedItemIds: Set<String>,
)

/** Transactional v2 material operations. Its database is not yet connected to the production UI. */
class FoodRepositoryV2(private val database: RecipeManagerDatabaseV2) {
    private val dao get() = database.foodDao()
    private val mapper = JsonInput.mapper()

    suspend fun find(id: String): FoodDetailsV2? = database.withTransaction {
        dao.find(id)?.let { details(it) }
    }

    suspend fun search(query: String): List<FoodDetailsV2> = database.withTransaction {
        dao.search(TextNormalizer.normalize(query)).map { details(it) }
    }

    suspend fun findByExactName(name: String): FoodMasterEntityV2? = database.withTransaction {
        val normalized = TextNormalizer.normalize(name)
        if (normalized.isEmpty()) return@withTransaction null
        val ids = dao.exactIds(normalized)
        check(ids.size <= 1) { "材料名・別名が競合しています" }
        ids.singleOrNull()?.let { dao.find(it) }
    }

    suspend fun save(input: FoodInputV2): FoodMasterEntityV2 = database.withTransaction {
        val name = input.name.trim()
        val normalized = TextNormalizer.normalize(name)
        require(normalized.isNotEmpty()) { "材料名を入力してください" }
        val unit = input.baseUnit.trim()
        require(unit.length <= 20) { "基本単位は20文字以内で入力してください" }
        require(input.foodGroups.all { it in FoodGroup.entries.map(FoodGroup::name) }) { "食品群に不明な値があります" }
        require(input.seasonMonths.all { it in 1..12 }) { "旬の月は1～12で指定してください" }
        val old = input.id?.let { current(it, input.expectedRevision) }
        require(old == null || old.userEditable) { "内蔵材料の属性は変更できません" }
        require(old != null || input.expectedRevision == null) { "新規材料に更新元の版は指定できません" }
        val aliases = normalizeAliases(input.aliases)
        validateNames(normalized, aliases, old?.id)
        val id = old?.id ?: UUID.randomUUID().toString()
        val oldAliases = if (old == null) emptyList() else dao.aliases(id)
        if (old != null && old.name == name && old.normalizedName == normalized && old.baseUnit == unit &&
            old.isMainIngredient == input.isMainIngredient && aliases.toMap() == oldAliases.associate { it.alias to it.normalizedAlias } &&
            dao.groups(id).toSet() == input.foodGroups && dao.seasons(id).toSet() == input.seasonMonths) {
            return@withTransaction old
        }
        val master = FoodMasterEntityV2(id, name, normalized, unit, input.isMainIngredient, true, old?.revision?.let(::nextRevision) ?: 1L)
        val now = Instant.now().toString()
        if (old == null) dao.insertMaster(master) else dao.updateMaster(master)
        replaceAliases(id, aliases, now)
        dao.clearGroups(id)
        dao.clearSeasons(id)
        if (input.foodGroups.isNotEmpty()) dao.insertGroups(input.foodGroups.sorted().map { FoodMasterGroupEntityV2(id, it) })
        if (input.seasonMonths.isNotEmpty()) dao.insertSeasons(input.seasonMonths.sorted().map { FoodMasterSeasonEntityV2(id, it) })
        // Attribute edits refresh the derived cache; ingredient amounts and units remain untouched.
        dao.recipesForFood(id).forEach { dao.updateNutrition(it.id, dao.recipeNutrition(it.id).joinToString(",")) }
        dao.markListsForFood(id)
        dao.markUnresolvedLists((listOfNotNull(old?.normalizedName, normalized) +
            oldAliases.map { it.normalizedAlias } + aliases.map { it.second }).distinct())
        master
    }

    /** Edits only user-owned aliases, including aliases attached to an immutable preset. */
    suspend fun replaceUserAliases(id: String, expectedRevision: Long, aliases: List<String>): FoodMasterEntityV2 = database.withTransaction {
        val old = current(id, expectedRevision)
        val normalized = normalizeAliases(aliases)
        validateNames(old.normalizedName, normalized, id)
        val oldAliases = dao.aliases(id)
        if (normalized.toMap() == oldAliases.associate { it.alias to it.normalizedAlias }) return@withTransaction old
        val master = old.copy(revision = nextRevision(old.revision))
        dao.updateMaster(master)
        replaceAliases(id, normalized, Instant.now().toString())
        dao.markListsForFood(id)
        dao.markUnresolvedLists((listOf(old.normalizedName) + oldAliases.map { it.normalizedAlias } + normalized.map { it.second }).distinct())
        master
    }

    suspend fun delete(id: String, expectedRevision: Long, confirmed: Boolean) = database.withTransaction {
        require(confirmed) { "材料の削除には確認が必要です" }
        val old = current(id, expectedRevision)
        require(old.userEditable) { "内蔵材料は削除できません" }
        require(dao.ingredientReferences(id) == 0 && dao.shoppingReferences(id) == 0 &&
            !DraftFoodMergeSupport.referencesFood(database, id)) { "参照中の材料は削除できません。統合を利用してください" }
        dao.markUnresolvedLists((listOf(old.normalizedName) + dao.aliases(id).map { it.normalizedAlias }).distinct())
        removeOwnedRows(id)
        dao.deleteMaster(id)
    }

    suspend fun merge(
        sourceId: String,
        targetId: String,
        sourceRevision: Long,
        targetRevision: Long,
        confirmed: Boolean,
    ): FoodMergeResultV2 = database.withTransaction {
        require(confirmed) { "材料の統合には確認が必要です" }
        require(sourceId != targetId) { "統合元と統合先には別の材料を指定してください" }
        val source = current(sourceId, sourceRevision)
        val target = current(targetId, targetRevision)
        require(source.userEditable) { "内蔵材料を統合元にはできません" }
        val sourceAliases = dao.aliases(sourceId)
        val targetAliases = dao.aliases(targetId)
        val combinedAliases = (targetAliases + sourceAliases).map { it.alias to it.normalizedAlias }
        validateNames(target.normalizedName, combinedAliases, targetId, sourceId)
        val now = Instant.now().toString()
        val changedRecipes = dao.recipesForFood(sourceId)
        val changedRevisions = changedRecipes.associate { it.id to (it.revision to nextRevision(it.revision)) }
        // Capture and mark affected lists before rewriting the references.
        dao.markListsForFood(sourceId)
        dao.markUnresolvedLists((listOf(source.normalizedName, target.normalizedName) +
            sourceAliases.map { it.normalizedAlias } + targetAliases.map { it.normalizedAlias }).distinct())
        val shopping = mergeShopping(dao.shoppingForMerge(sourceId, targetId), sourceId, target)
        if (shopping.removedIds.isNotEmpty()) dao.deleteShoppingItems(shopping.removedIds)
        if (shopping.items.isNotEmpty()) dao.insertShoppingItems(shopping.items)
        if (shopping.listIds.isNotEmpty()) dao.markShoppingLists(shopping.listIds.toList())
        dao.moveIngredients(sourceId, targetId)
        changedRecipes.forEach { recipe ->
            dao.updateMergedRecipe(recipe.id, dao.recipeNutrition(recipe.id).joinToString(","), changedRevisions.getValue(recipe.id).second, now)
        }
        val targetAfter = if (sourceAliases.isNotEmpty()) target.copy(revision = nextRevision(target.revision)) else target
        if (targetAfter != target) {
            dao.updateMaster(targetAfter)
            dao.markListsForFood(targetId)
        }
        dao.moveAliases(sourceId, targetId, now)
        DraftFoodMergeSupport.merge(database, sourceId, targetId, source.revision, target.revision,
            targetAfter.revision, changedRevisions, now)
        removeOwnedRows(sourceId)
        dao.deleteMaster(sourceId)
        FoodMergeResultV2(targetAfter, changedRevisions.keys, shopping.mixedExcludedIds)
    }

    private suspend fun details(master: FoodMasterEntityV2) = FoodDetailsV2(
        master, dao.aliases(master.id), dao.builtInAliases(master.id), dao.groups(master.id).toSet(), dao.seasons(master.id).toSet(),
    )

    private suspend fun current(id: String, expectedRevision: Long?): FoodMasterEntityV2 {
        val current = dao.find(id) ?: error("材料が見つかりません")
        require(expectedRevision != null && current.revision == expectedRevision) { "材料が別の操作で変更されています。再読込してください" }
        return current
    }

    private fun normalizeAliases(aliases: List<String>): List<Pair<String, String>> = aliases.map {
        val display = it.trim()
        val normalized = TextNormalizer.normalize(display)
        require(normalized.isNotEmpty()) { "別名を空にはできません" }
        display to normalized
    }.also { require(it.map { alias -> alias.second }.toSet().size == it.size) { "別名が重複しています" } }

    private suspend fun validateNames(name: String, aliases: List<Pair<String, String>>, ownId: String?, removedId: String? = null) {
        val proposed = listOf(name) + aliases.map { it.second }
        require(proposed.toSet().size == proposed.size) { "標準名と別名が重複しています" }
        val existing = dao.names().filter { it.foodMasterId != ownId && it.foodMasterId != removedId }
        require(existing.none { it.normalizedName in proposed }) { "標準名・内蔵別名・利用者別名が既存材料と競合しています" }
        // Existing built-in aliases remain immutable and must not be shadowed by user aliases.
        if (ownId != null) {
            val builtIns = dao.builtInAliases(ownId).map { it.normalizedAlias }.toSet()
            require(aliases.none { it.second in builtIns }) { "内蔵別名と利用者別名が重複しています" }
        }
    }

    private suspend fun replaceAliases(id: String, aliases: List<Pair<String, String>>, now: String) {
        val old = dao.aliases(id).associateBy { it.normalizedAlias }
        dao.clearAliases(id)
        val rows = aliases.map { (display, normalized) ->
            val previous = old[normalized]
            UserFoodAliasEntityV2(previous?.id ?: UUID.randomUUID().toString(), id, display, normalized,
                if (previous?.alias == display) previous.updatedAt else now)
        }
        if (rows.isNotEmpty()) dao.insertAliases(rows)
    }

    private suspend fun removeOwnedRows(id: String) {
        dao.clearAliases(id)
        dao.clearBuiltInAliases(id)
        dao.clearGroups(id)
        dao.clearSeasons(id)
    }

    private data class ShoppingMerge(
        val removedIds: List<String>, val items: List<ShoppingItemEntityV2>,
        val listIds: Set<String>, val mixedExcludedIds: Set<String>,
    )

    private fun mergeShopping(rows: List<ShoppingItemEntityV2>, sourceId: String, target: FoodMasterEntityV2): ShoppingMerge {
        val removed = mutableListOf<String>()
        val merged = mutableListOf<ShoppingItemEntityV2>()
        val mixed = mutableSetOf<String>()
        val lists = mutableSetOf<String>()
        rows.groupBy { it.shoppingListId to it.unit.trim() }.forEach { (key, group) ->
            if (group.none { it.foodMasterId == sourceId }) return@forEach
            require(group.all { it.amount != null || it.hasUnknownAmount }) { "買い物数量の不明情報が不正です" }
            val amounts = group.mapNotNull { it.amount?.let(::BigDecimal) }
            require(amounts.all { it > BigDecimal.ZERO }) { "買い物数量は正数で指定してください" }
            val sum = amounts.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)?.toPlainString()
            val usages = linkedSetOf<JsonNode>()
            group.forEach { row ->
                val document = mapper.readTree(row.recipeUsagesJson)
                require(document != null && document.isArray) { "買い物の利用料理情報が不正です" }
                document.forEach { usage ->
                    require(usage.isObject && usage.path("recipeNameSnapshot").isTextual &&
                        (usage.path("recipeId").isNull || usage.path("recipeId").isTextual)) { "買い物の利用料理情報が不正です" }
                    usages.add(usage)
                }
            }
            val preferred = group.firstOrNull { it.foodMasterId == target.id } ?: group.first()
            val item = preferred.copy(
                name = target.name, normalizedName = target.normalizedName, foodMasterId = target.id,
                amount = sum, hasUnknownAmount = group.any { it.hasUnknownAmount }, unit = key.second,
                aggregationKey = mapper.createArrayNode().add("food").add(target.id).add(key.second).toString(),
                recipeUsagesJson = mapper.createArrayNode().addAll(usages.toList()).toString(),
                checked = false, excluded = group.all { it.excluded },
            )
            removed += group.map { it.id }
            merged += item
            lists += key.first
            if (group.map { it.excluded }.distinct().size > 1) mixed += item.id
        }
        return ShoppingMerge(removed, merged, lists, mixed)
    }

    private fun nextRevision(revision: Long): Long {
        require(revision in 1 until Long.MAX_VALUE) { "材料またはレシピの版が範囲外です" }
        return revision + 1
    }
}
