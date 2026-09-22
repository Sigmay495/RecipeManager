package jp.local.recipemanager.data.local

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

class BuiltInHolidayLoader(private val context: Context) {
    suspend fun ensureLoaded(database: RecipeManagerDatabase) {
        val dao = database.systemDataDao()
        if (dao.getMetadata(VERSION_KEY) == VERSION) return
        val json = context.assets.open("masters/holidays_2026_2027.json").bufferedReader().use { it.readText() }
        val array = JSONArray(json)
        val holidays = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val date = LocalDate.parse(item.getString("date"))
            HolidayEntity(date, item.getString("name"), date.year)
        }
        dao.insertHolidays(holidays)
        dao.putMetadata(MetadataEntity(VERSION_KEY, VERSION))
    }

    private companion object {
        const val VERSION_KEY = "holiday_data_version"
        const val VERSION = "2026-2027-v1"
    }
}
