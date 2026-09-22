package jp.local.recipemanager.feature.shopping

import jp.local.recipemanager.data.local.*
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class ShoppingWeek(val data: ShoppingListWithItems?, val menuUpdatedAt: Instant?) {
    val needsRegeneration get() = data != null && menuUpdatedAt != null && menuUpdatedAt > data.list.sourceMenuUpdatedAt
}

class ShoppingRepository(private val database: RecipeManagerDatabase) {
    fun observe(start: LocalDate): Flow<ShoppingListWithItems?> = database.shoppingDao().observeWeek(start)
    suspend fun menuUpdatedAt(start: LocalDate) = database.menuDao().findByWeek(start)?.menu?.updatedAt

    suspend fun generate(start: LocalDate) {
        val menu = database.menuDao().findByWeek(start) ?: error("この週の献立がありません")
        val recipeMap = database.recipeDao().getAll().associateBy { it.recipe.id }
        val sources = menu.days.flatMap { day -> day.items.flatMap { item ->
            recipeMap[item.recipeId]?.ingredients.orEmpty().map { ShoppingSource(it, item.recipeNameSnapshot) }
        } }
        require(sources.isNotEmpty()) { "献立に材料が登録されていません" }
        val dao = database.shoppingDao()
        val existing = dao.findWeek(start)
        val id = existing?.list?.id ?: UUID.randomUUID().toString()
        val states = existing?.items.orEmpty().associateBy { Triple(it.normalizedName, it.unit, it.amount == null) }
        val items = ShoppingListBuilder.build(sources).map { line ->
            val old = states[Triple(line.normalizedName, line.unit, line.amount == null)]
            ShoppingItemEntity(old?.id ?: UUID.randomUUID().toString(), id, line.name, line.normalizedName, line.amount, line.unit, line.recipeNames, old?.checked ?: false, old?.excluded ?: false)
        }
        dao.replace(ShoppingListEntity(id, start, menu.menu.updatedAt, existing?.list?.createdAt ?: Instant.now()), items)
    }

    suspend fun update(item: ShoppingItemEntity) = database.shoppingDao().updateItem(item)
}
