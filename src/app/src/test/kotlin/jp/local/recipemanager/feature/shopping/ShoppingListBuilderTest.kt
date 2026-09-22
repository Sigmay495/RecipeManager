package jp.local.recipemanager.feature.shopping

import jp.local.recipemanager.data.local.RecipeIngredientEntity
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ShoppingListBuilderTest {
    @Test fun gramsAndKilogramsAreCombinedInGrams() {
        val result = ShoppingListBuilder.build(listOf(source("豚肉", "豚肉", "500", "g", "炒め物", 1), source("豚肉", "豚肉", "0.3", "kg", "豚汁", 2)))
        assertEquals(1, result.size)
        assertEquals(BigDecimal("8E+2"), result.single().amount)
        assertEquals("g", result.single().unit)
        assertEquals("炒め物、豚汁", result.single().recipeNames)
    }

    @Test fun incompatibleUnitsRemainOnSeparateLines() {
        val result = ShoppingListBuilder.build(listOf(source("玉ねぎ", "玉ねぎ", "2", "個", "カレー", 1), source("玉ねぎ", "玉ねぎ", "1", "袋", "スープ", 2)))
        assertEquals(2, result.size)
    }

    @Test fun unknownAmountIsSeparateFromKnownAmount() {
        val result = ShoppingListBuilder.build(listOf(source("塩", "塩", null, "g", "スープ", 1), source("塩", "塩", "5", "g", "炒め物", 2)))
        assertEquals(2, result.size)
        assertTrue(result.any { it.amount == null })
        assertTrue(result.any { it.amount != null })
    }

    private fun source(name: String, normalized: String, amount: String?, unit: String, recipe: String, order: Int) = ShoppingSource(
        RecipeIngredientEntity("i$order", "r$order", order, name, normalized, amount?.toBigDecimal(), unit, "", null), recipe,
    )
}
