package jp.local.recipemanager.data.local

import jp.local.recipemanager.domain.model.FoodGroup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class RoomConvertersTest {
    private val converters = RoomConverters()

    @Test fun valuesRoundTripUsingStableTextFormats() {
        val date = LocalDate.of(2026, 9, 20)
        val instant = Instant.parse("2026-09-20T01:02:03Z")
        val decimal = BigDecimal("12.340")
        val groups = linkedSetOf(FoodGroup.VEGETABLE, FoodGroup.MEAT)
        assertEquals(date, converters.stringToLocalDate(converters.localDateToString(date)))
        assertEquals(instant, converters.stringToInstant(converters.instantToString(instant)))
        assertEquals(decimal, converters.stringToDecimal(converters.decimalToString(decimal)))
        assertEquals(groups, converters.stringToFoodGroups(converters.foodGroupsToString(groups)))
    }
}
