package jp.local.recipemanager.feature.shopping

import jp.local.recipemanager.data.local.RecipeIngredientEntity
import java.math.BigDecimal

data class ShoppingSource(val ingredient: RecipeIngredientEntity, val recipeName: String)
data class ShoppingLine(val name: String, val normalizedName: String, val amount: BigDecimal?, val unit: String, val recipeNames: String)

object ShoppingListBuilder {
    fun build(sources: List<ShoppingSource>): List<ShoppingLine> = sources.groupBy { source ->
        val ingredient = source.ingredient
        Triple(ingredient.normalizedName, dimension(ingredient.unit), ingredient.amount == null)
    }.map { (_, values) ->
        val first = values.first().ingredient
        val dimension = dimension(first.unit)
        val amounts = values.map { convertedAmount(it.ingredient.amount, it.ingredient.unit, dimension) }
        val canSum = amounts.all { it != null } && amounts.isNotEmpty()
        ShoppingLine(
            name = first.name,
            normalizedName = first.normalizedName,
            amount = if (canSum) amounts.filterNotNull().fold(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros() else null,
            unit = canonicalUnit(dimension, first.unit),
            recipeNames = values.map { it.recipeName }.distinct().joinToString("、"),
        )
    }.sortedBy { it.normalizedName }

    private fun dimension(unit: String) = when (unit.lowercase()) { "g", "kg" -> "mass"; "ml", "l" -> "volume"; else -> "exact:${unit.trim()}" }
    private fun canonicalUnit(dimension: String, original: String) = when (dimension) { "mass" -> "g"; "volume" -> "ml"; else -> original.trim() }
    private fun convertedAmount(amount: BigDecimal?, unit: String, dimension: String): BigDecimal? = amount?.let {
        when { dimension == "mass" && unit.equals("kg", true) -> it * BigDecimal(1000); dimension == "volume" && unit.equals("l", true) -> it * BigDecimal(1000); else -> it }
    }
}
