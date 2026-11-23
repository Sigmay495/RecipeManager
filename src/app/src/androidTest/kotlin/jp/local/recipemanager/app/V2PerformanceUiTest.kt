package jp.local.recipemanager.app

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import jp.local.recipemanager.MainActivity
import jp.local.recipemanager.app.theme.RecipeManagerTheme
import jp.local.recipemanager.app.v2.AppViewModelV2
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.core.text.TextNormalizer
import jp.local.recipemanager.data.local.v2.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

class V2PerformanceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: AppViewModelV2

    @Test
    fun acceptanceTimingsFromUiActionToResult() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("recipeManagerPerformance") == "true")
        assertEquals(26, Build.VERSION.SDK_INT)
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val filename = "v2-performance-${UUID.randomUUID()}.db"
        val database = DatabaseRuntimeV2.build(application, filename)
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val samples = linkedMapOf("list" to mutableListOf<Long>(), "search" to mutableListOf(),
            "proposal" to mutableListOf(), "import" to mutableListOf())
        val importFile = createImportFile(application)
        try {
            val initial = runBlocking { fixture(application, database) }
            compose.activityRule.scenario.onActivity { activity ->
                val factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModelV2(application, database) as T
                }
                vm = ViewModelProvider.create(owner, factory)[AppViewModelV2::class]
                activity.setContent {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        RecipeManagerTheme { RecipeManagerApp() }
                    }
                }
            }
            waitFor("献立を確認")
            repeat(3) {
                runBlocking {
                    database.backupDao().replace(initial)
                    vm.navigate("home").join()
                }
                waitFor("献立を確認", visible = true)
                samples.getValue("list") += measure("検索結果 500件") { tab("レシピ").performClick() }
                samples.getValue("search") += measure("検索結果 1件") {
                    compose.onNodeWithText("料理名・材料名で検索").performTextReplacement("素材999")
                }
                runBlocking {
                    vm.navigate("menu").join()
                    vm.changeWeek(1).join()
                }
                assertTrue(vm.state.value.week!!.days.none { it.day.noMeal })
                samples.getValue("proposal") += measure("提案確認") {
                    compose.onNodeWithText("1週間の献立を自動提案").performClick()
                }
                assertTrue(vm.state.value.editor!!.payload.path("days").all { it.path("items").size() > 0 })

                runBlocking { vm.navigate("import").join() }
                vm.importFile(Uri.fromFile(importFile))
                waitFor("確認した内容を登録")
                assertEquals(100, vm.state.value.importReview!!.entries.size)
                compose.onNodeWithText("確認した内容を登録").performScrollTo().performClick()
                waitFor("Importを確定しますか")
                samples.getValue("import") += measure("100件を追加、0件をスキップしました") {
                    compose.onNodeWithText("確認して実行").performScrollTo().performClick()
                }
                assertEquals(600, runBlocking { database.backupDao().recipes().size })
            }
            val limits = mapOf("list" to 2000L, "search" to 2000L, "proposal" to 5000L, "import" to 10000L)
            samples.forEach { (name, times) -> Log.i("RecipeV2Perf", "$name: $times ms (limit ${limits.getValue(name)} ms)") }
            val exceeded = samples.filter { (name, times) -> times.max() > limits.getValue(name) }
            assertTrue("Slowest run must meet each limit in milliseconds: $exceeded", exceeded.isEmpty())
        } finally {
            compose.runOnUiThread { owner.viewModelStore.clear() }
            database.close()
            application.deleteDatabase(filename)
            importFile.delete()
        }
    }

    private fun tab(label: String) = compose.onNode(hasText(label) and hasClickAction())

    private fun measure(result: String, action: () -> Unit): Long {
        compose.waitForIdle()
        val started = SystemClock.elapsedRealtime()
        action()
        waitFor(result, visible = true)
        return (SystemClock.elapsedRealtime() - started).also { Log.i("RecipeV2Perf", "$result: $it ms") }
    }

    private fun waitFor(label: String, visible: Boolean = false) = compose.waitUntil(timeoutMillis = 90000) {
        check(vm.state.value.error == null) { "Waiting for $label: ${vm.state.value.error}" }
        !vm.state.value.busy && if (visible) compose.onNodeWithText(label).isDisplayed()
        else compose.onAllNodesWithText(label).fetchSemanticsNodes().isNotEmpty()
    }

    private suspend fun fixture(context: Context, database: RecipeManagerDatabaseV2): BackupRowsV2 {
        DatabaseRuntimeV2.initialize(context, database)
        val dao = database.backupDao()
        val empty = dao.snapshot()
        val today = LocalDate.now()
        val start = today.minusDays(today.dayOfWeek.value - 1L).plusWeeks(1)
        val timestamp = "2020-01-01T00:00:00Z"
        val foods = (empty.foods.size until 1000).map { index ->
            val name = "素材$index"
            FoodMasterEntityV2(id("food-$index"), name, TextNormalizer.normalize(name), "g", true, true, 1)
        }
        val historyDates = generateSequence(today.minusYears(5)) { it.plusDays(1) }.takeWhile { it < today }.toList()
        val categories = listOf("ONE_DISH", "MAIN", "SIDE", "SOUP")
        val recipes = (0 until 500).map { index ->
            val name = "性能料理${index.toString().padStart(3, '0')}"
            val lastCookedAt = historyDates.indices.last { it % 500 == index }.let { historyDates[it].toString() }
            RecipeEntityV2(id("recipe-$index"), name, TextNormalizer.normalize(name), categories[index % 4], 15 + index % 45,
                listOf("EASY", "NORMAL", "HARD")[index % 3], null, "", lastCookedAt, "GRAIN,MEAT,VEGETABLE",
                if (index % 3 == 0) "high" else "normal", 1, timestamp, timestamp)
        }
        val ingredients = recipes.flatMapIndexed { index, recipe ->
            val selected = if (index == 499) foods.takeLast(3) else (0..2).map { foods[(index * 3 + it) % (foods.size - 3)] }
            selected.mapIndexed { order, food ->
                RecipeIngredientEntityV2(id("ingredient-$index-$order"), recipe.id, order, food.name,
                    food.normalizedName, "100", "g", "", food.id)
            }
        }
        val histories = historyDates.mapIndexed { index, date ->
            CookingHistoryEntityV2(id("history-$index"), date.toString(), timestamp, timestamp)
        }
        val historyItems = histories.mapIndexed { index, history ->
            val recipe = recipes[index % recipes.size]
            HistoryItemEntityV2(id("history-item-$index"), history.id, recipe.id, recipe.name, recipe.category)
        }
        val week = WeeklyMenuEntityV2(id("performance-week"), start.toString(), timestamp, timestamp, 1)
        val days = (0L..6L).map { offset ->
            MenuDayEntityV2(id("performance-day-$offset"), week.id, start.plusDays(offset).toString(), false, "", "",
                "explicit", false, 1)
        }
        val seeded = empty.copy(
            foods = foods, recipes = recipes, ingredients = ingredients,
            groups = foods.flatMap { food ->
                listOf("GRAIN", "MEAT", "VEGETABLE").map { FoodMasterGroupEntityV2(food.id, it) }
            },
            seasons = foods.mapIndexed { index, food -> FoodMasterSeasonEntityV2(food.id, index % 12 + 1) },
            histories = histories, historyItems = historyItems, menus = listOf(week), days = days,
        )
        assertEquals(1000, empty.foods.size + seeded.foods.size)
        return seeded
    }

    private fun createImportFile(context: Context): File {
        val mapper = JsonInput.mapper()
        val document = mapper.createObjectNode().apply {
            put("schemaVersion", "1.0")
            put("fileType", "recipe-import")
            put("generatedAt", "2020-01-01T00:00:00Z")
            val recipes = putArray("recipes")
            repeat(100) { index ->
                recipes.addObject().apply {
                    put("name", "追加試験${index.toString().padStart(3, '0')}")
                    put("category", "one_dish")
                    put("effortLevel", "normal")
                    put("memo", "")
                    putArray("warnings")
                    putArray("ingredients").addObject().apply {
                        put("name", "素材${700 + index}")
                        put("amount", 100)
                        put("unit", "g")
                        put("note", "")
                    }
                }
            }
        }
        return File.createTempFile("v2-performance-", ".json", context.cacheDir).also { mapper.writeValue(it, document) }
    }

    private fun id(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()
}
