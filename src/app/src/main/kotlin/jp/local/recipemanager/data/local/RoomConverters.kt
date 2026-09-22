package jp.local.recipemanager.data.local

import androidx.room.TypeConverter
import jp.local.recipemanager.domain.model.FoodGroup
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class RoomConverters {
    @TypeConverter fun localDateToString(value: LocalDate?): String? = value?.toString()
    @TypeConverter fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)
    @TypeConverter fun instantToString(value: Instant?): String? = value?.toString()
    @TypeConverter fun stringToInstant(value: String?): Instant? = value?.let(Instant::parse)
    @TypeConverter fun decimalToString(value: BigDecimal?): String? = value?.toPlainString()
    @TypeConverter fun stringToDecimal(value: String?): BigDecimal? = value?.let(::BigDecimal)
    @TypeConverter fun foodGroupsToString(value: Set<FoodGroup>): String =
        value.map(FoodGroup::name).sorted().joinToString(",")
    @TypeConverter fun stringToFoodGroups(value: String): Set<FoodGroup> =
        value.takeIf(String::isNotBlank)?.split(',')?.mapTo(linkedSetOf(), FoodGroup::valueOf).orEmpty()
}
