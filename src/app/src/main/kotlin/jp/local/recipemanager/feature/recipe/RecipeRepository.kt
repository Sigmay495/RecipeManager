package jp.local.recipemanager.feature.recipe

import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.data.local.FoodMasterDao
import jp.local.recipemanager.data.local.RecipeDao
import jp.local.recipemanager.data.local.RecipeEntity
import jp.local.recipemanager.data.local.RecipeIngredientEntity
import jp.local.recipemanager.data.local.RecipeWithIngredients
import jp.local.recipemanager.domain.model.RecipeStatus
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RecipeRepository(
    private val recipeDao: RecipeDao,
    private val foodMasterDao: FoodMasterDao,
    private val clock: Clock = Clock.systemUTC(),
) {
    val recipes: Flow<List<RecipeWithIngredients>> = recipeDao.observeAll()

    fun observeRecipe(id: String): Flow<RecipeWithIngredients?> = recipeDao.observeById(id)

    suspend fun draft(id: String): RecipeDraft? = recipeDao.findById(id)?.let { record ->
        RecipeDraft(
            id = record.recipe.id,
            name = record.recipe.name,
            category = record.recipe.category,
            ingredients = record.ingredients.sortedBy { it.displayOrder }.map {
                IngredientDraft(it.name, it.amount?.toPlainString().orEmpty(), it.unit, it.note)
            },
            cookingTime = record.recipe.cookingTimeMinutes?.toString().orEmpty(),
            effortLevel = record.recipe.effortLevel,
            initialLastCookedAt = record.recipe.initialLastCookedAt?.toString().orEmpty(),
            sourceUrl = record.recipe.sourceUrl.orEmpty(),
            memo = record.recipe.memo,
        )
    }

    suspend fun validate(draft: RecipeDraft): RecipeValidation {
        val errors = RecipeValidator.validate(draft, LocalDate.now(clock))
        if (errors.isNotEmpty()) return RecipeValidation(errors)
        val excludedId = draft.id.orEmpty()
        val duplicateName = recipeDao.countByNormalizedName(TextNormalizer.normalize(draft.name), excludedId) > 0
        val duplicateUrl = draft.sourceUrl.trim().takeIf(String::isNotEmpty)?.let {
            recipeDao.countBySourceUrl(it, excludedId) > 0
        } == true
        return RecipeValidation(
            duplicateWarning = when {
                duplicateName && duplicateUrl -> "同じ料理名とURLのレシピがあります。保存してよいか確認してください。"
                duplicateName -> "同じ料理名のレシピがあります。保存してよいか確認してください。"
                duplicateUrl -> "同じURLのレシピがあります。保存してよいか確認してください。"
                else -> null
            },
        )
    }

    suspend fun save(draft: RecipeDraft): String {
        check(RecipeValidator.validate(draft, LocalDate.now(clock)).isEmpty())
        val existing = draft.id?.let { recipeDao.findById(it)?.recipe }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val now = Instant.now(clock)
        val masters = foodMasterDao.getAll()
        val masterByName = buildMap {
            masters.forEach { details ->
                put(details.master.normalizedName, details)
                details.aliases.forEach { put(it.normalizedAlias, details) }
            }
        }
        val ingredients = draft.ingredients.mapIndexed { index, item ->
            val normalizedName = TextNormalizer.normalize(item.name)
            RecipeIngredientEntity(
                id = existing?.let { old ->
                    recipeDao.findById(old.id)?.ingredients?.getOrNull(index)?.id
                } ?: UUID.randomUUID().toString(),
                recipeId = id,
                displayOrder = index,
                name = item.name.trim(),
                normalizedName = normalizedName,
                amount = item.amount.takeIf(String::isNotBlank)?.toBigDecimal(),
                unit = item.unit.trim(),
                note = item.note.trim(),
                foodMasterId = masterByName[normalizedName]?.master?.id,
            )
        }
        val nutritionGroups = ingredients.mapNotNull { it.foodMasterId }
            .flatMap { masterId -> masters.first { it.master.id == masterId }.groups.map { it.foodGroup } }
            .toSet()
        val recipe = RecipeEntity(
            id = id,
            name = draft.name.trim(),
            normalizedName = TextNormalizer.normalize(draft.name),
            category = draft.category,
            cookingTimeMinutes = draft.cookingTime.takeIf(String::isNotBlank)?.toInt(),
            effortLevel = draft.effortLevel,
            sourceUrl = draft.sourceUrl.trim().ifEmpty { null },
            memo = draft.memo.trim(),
            lastCookedAt = existing?.lastCookedAt ?: draft.initialLastCookedAt.takeIf(String::isNotBlank)?.let(LocalDate::parse),
            initialLastCookedAt = existing?.initialLastCookedAt ?: draft.initialLastCookedAt.takeIf(String::isNotBlank)?.let(LocalDate::parse),
            nutritionGroups = nutritionGroups,
            status = existing?.status ?: RecipeStatus.ACTIVE,
            deletedAt = existing?.deletedAt,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (existing == null) recipeDao.insert(recipe, ingredients) else recipeDao.update(recipe, ingredients)
        return id
    }

    suspend fun delete(id: String) = recipeDao.markDeleted(id, Instant.now(clock))
    suspend fun restore(id: String) = recipeDao.restore(id, Instant.now(clock))
}
