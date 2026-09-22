package jp.local.recipemanager.feature.menu

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.local.recipemanager.data.local.*
import jp.local.recipemanager.domain.model.MenuRole
import jp.local.recipemanager.domain.model.RecipeCategory
import java.time.LocalDate
import java.time.format.DateTimeParseException
import kotlinx.coroutines.launch

@Composable
fun MenuPlanScreen(viewModel: MenuViewModel, onNoMeal: (LocalDate) -> Unit, onDetail: (LocalDate) -> Unit) {
    val start by viewModel.weekStart.collectAsState()
    val week by viewModel.week.collectAsState()
    val holidays by viewModel.holidays.collectAsState()
    val recipes by viewModel.recipes.collectAsState()
    val message by viewModel.message.collectAsState()
    val evaluation by viewModel.evaluation.collectAsState()
    val proposing by viewModel.proposing.collectAsState()
    LaunchedEffect(start, week) { viewModel.evaluateWeek() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("献立", style = MaterialTheme.typography.headlineMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ viewModel.moveWeek(-7) }) { Text("前の週") }
                Text("$start ～ ${start.plusDays(4)}", modifier = Modifier.padding(top = 12.dp))
                TextButton({ viewModel.moveWeek(7) }) { Text("次の週") }
            }
            Button(viewModel::proposeWeek, enabled = !proposing && recipes.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(if (proposing) "提案を作成中…" else "1週間の献立を自動提案")
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            evaluation?.let { EvaluationCard(it) }
        }
        items((0L..4L).map(start::plusDays)) { date ->
            val holiday = holidays.firstOrNull { it.date == date }
            val saved = week?.days?.firstOrNull { it.day.date == date }
            DayCard(date, holiday, saved, recipes.map { it.recipe }, { viewModel.saveMenu(date, it) }, { onNoMeal(date) }, { onDetail(date) })
        }
    }
}

@Composable
private fun EvaluationCard(value: MenuEvaluation) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("献立の評価", fontWeight = FontWeight.Bold)
            Text("手間 ${value.effortPenalty} / 栄養 ${value.nutritionPenalty} / 食材ロス ${value.wastePenalty} / 旬 ${value.seasonalScore}", style = MaterialTheme.typography.bodySmall)
            if (value.warnings.isEmpty()) Text("大きな偏りはありません")
            else value.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.onSecondaryContainer) }
        }
    }
}

@Composable
private fun DayCard(date: LocalDate, holiday: HolidayEntity?, saved: MenuDayWithItems?, recipes: List<RecipeEntity>, onSave: (Map<MenuRole, RecipeEntity>) -> Unit, onNoMeal: () -> Unit, onDetail: () -> Unit) {
    var oneDish by remember(saved) { mutableStateOf(saved?.items?.singleOrNull()?.role == MenuRole.ONE_DISH) }
    val selected = remember(saved) { mutableStateMapOf<MenuRole, RecipeEntity>().apply { saved?.items?.forEach { item -> recipes.firstOrNull { it.id == item.recipeId }?.let { put(item.role, it) } } } }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(date.toString(), fontWeight = FontWeight.Bold)
        when {
            holiday != null -> Text("祝日：${holiday.name}")
            saved?.day?.noMeal == true -> { Text("ご飯不要${saved.day.noMealReason.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()}"); Button(onNoMeal) { Text("変更・解除") } }
            else -> {
                Row { FilterChip(oneDish, { oneDish = true; selected.clear() }, { Text("一品もの") }); Spacer(Modifier.width(8.dp)); FilterChip(!oneDish, { oneDish = false; selected.clear() }, { Text("3品") }) }
                val roles = if (oneDish) listOf(MenuRole.ONE_DISH) else listOf(MenuRole.MAIN, MenuRole.SIDE, MenuRole.SOUP)
                roles.forEach { role -> RecipePicker(role, recipes.filter { it.category.name == role.name }, selected[role]) { selected[role] = it } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onSave(selected.toMap()) }, enabled = roles.all(selected::containsKey)) { Text("保存") }
                    OutlinedButton(onNoMeal) { Text("ご飯不要") }
                    if (saved?.items?.isNotEmpty() == true) TextButton(onDetail) { Text("詳細・作った") }
                }
            }
        }
    } }
}

@Composable
private fun RecipePicker(role: MenuRole, recipes: List<RecipeEntity>, selected: RecipeEntity?, onSelect: (RecipeEntity) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text("${role.label()}：${selected?.name ?: "選択してください"}") }
        DropdownMenu(open, { open = false }) { recipes.forEach { recipe -> DropdownMenuItem({ Text(recipe.name) }, { onSelect(recipe); open = false }) } }
    }
}

@Composable
fun MenuDetailScreen(date: LocalDate, viewModel: MenuViewModel, onBack: () -> Unit) {
    var record by remember { mutableStateOf<MenuDayWithItems?>(null) }
    val recipes by viewModel.recipes.collectAsState()
    var selected by remember { mutableStateOf(setOf<String>()) }
    val message by viewModel.message.collectAsState()
    LaunchedEffect(date, message) { record = viewModel.day(date); if (selected.isEmpty()) selected = record?.items.orEmpty().map { it.id }.toSet() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("献立詳細 $date", style = MaterialTheme.typography.headlineSmall); TextButton(onBack) { Text("戻る") }; message?.let { Text(it) } }
        items(record?.items.orEmpty()) { item ->
            val recipe = recipes.firstOrNull { it.recipe.id == item.recipeId }
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row { Checkbox(item.id in selected, { checked -> selected = if (checked) selected + item.id else selected - item.id }); Text("${item.role.label()}：${item.recipeNameSnapshot}", modifier = Modifier.padding(top = 12.dp)) }
                if (recipe == null || recipe.recipe.status.name == "DELETED") Text("削除済みレシピです", color = MaterialTheme.colorScheme.error)
                recipe?.ingredients?.forEach { Text("・${it.name} ${it.amount ?: ""}${it.unit}") }
                recipe?.recipe?.sourceUrl?.takeIf(String::isNotBlank)?.let { Text("作り方: $it") }
            } }
        }
        item { Button({ viewModel.recordCooking(date, record?.items.orEmpty(), selected) }, enabled = selected.isNotEmpty()) { Text("選択した料理を作った") } }
    }
}

@Composable
fun NoMealDayScreen(initialDate: LocalDate?, viewModel: MenuViewModel, onBack: () -> Unit) {
    var dateText by remember { mutableStateOf((initialDate ?: LocalDate.now()).toString()) }
    var reason by remember { mutableStateOf("") }; var memo by remember { mutableStateOf("") }
    var confirmDate by remember { mutableStateOf<LocalDate?>(null) }; var error by remember { mutableStateOf<String?>(null) }
    val existing by viewModel.noMealDays.collectAsState()
    suspend fun prepare() {
        try { val date = LocalDate.parse(dateText); if (viewModel.day(date)?.items?.isNotEmpty() == true) confirmDate = date else viewModel.saveNoMeal(date, reason, memo) }
        catch (_: DateTimeParseException) { error = "日付はYYYY-MM-DD形式で入力してください" }
    }
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("ご飯不要日", style = MaterialTheme.typography.headlineSmall); TextButton(onBack) { Text("戻る") }
            OutlinedTextField(dateText, { dateText = it }, label = { Text("日付（平日）") }); OutlinedTextField(reason, { reason = it }, label = { Text("理由（任意）") }); OutlinedTextField(memo, { if (it.length <= 200) memo = it }, label = { Text("メモ（任意・200文字以内）") }); error?.let { Text(it, color = MaterialTheme.colorScheme.error) }; Button({ scope.launch { prepare() } }) { Text("保存") }; HorizontalDivider(); Text("登録済み") }
        items(existing) { day -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${day.date} ${day.noMealReason}"); TextButton({ viewModel.deleteNoMeal(day.date) }) { Text("解除") } } }
    }
    confirmDate?.let { date -> AlertDialog({ confirmDate = null }, { TextButton({ viewModel.saveNoMeal(date, reason, memo); confirmDate = null }) { Text("解除して登録") } }, dismissButton = { TextButton({ confirmDate = null }) { Text("キャンセル") } }, title = { Text("献立を解除しますか？") }, text = { Text("この日の保存済み献立は削除されます。") }) }
}

@Composable
fun MenuHistoryScreen(viewModel: MenuViewModel, onBack: () -> Unit) {
    val histories by viewModel.histories.collectAsState(); var from by remember { mutableStateOf("") }; var to by remember { mutableStateOf("") }
    fun inRange(date: LocalDate) = (from.toLocalDateOrNull()?.let { !date.isBefore(it) } ?: true) && (to.toLocalDateOrNull()?.let { !date.isAfter(it) } ?: true)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("献立履歴", style = MaterialTheme.typography.headlineSmall); TextButton(onBack) { Text("戻る") }; Row { OutlinedTextField(from, { from = it }, Modifier.weight(1f), label = { Text("開始日") }); Spacer(Modifier.width(8.dp)); OutlinedTextField(to, { to = it }, Modifier.weight(1f), label = { Text("終了日") }) } }
        items(histories.filter { inRange(it.history.cookedDate) }) { record -> HistoryCard(record, viewModel) }
    }
}

@Composable
private fun HistoryCard(record: HistoryWithItems, viewModel: MenuViewModel) {
    var editing by remember { mutableStateOf(false) }; var selected by remember(record) { mutableStateOf(record.items.map { it.id }.toSet()) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text(record.history.cookedDate.toString(), fontWeight = FontWeight.Bold)
        record.items.forEach { item -> Row { if (editing) Checkbox(item.id in selected, { if (it) selected += item.id else selected -= item.id }); Text("${item.role.label()}：${item.recipeNameSnapshot}", Modifier.padding(top = if (editing) 12.dp else 0.dp)) } }
        Row { if (editing) Button({ viewModel.updateHistory(record, selected); editing = false }) { Text("変更を保存") } else TextButton({ editing = true }) { Text("修正") }; TextButton({ viewModel.deleteHistory(record.history.id) }) { Text("削除") } }
    } }
}

private fun MenuRole.label() = when (this) { MenuRole.ONE_DISH -> "一品もの"; MenuRole.MAIN -> "主菜"; MenuRole.SIDE -> "副菜"; MenuRole.SOUP -> "汁物" }
private fun String.toLocalDateOrNull() = try { takeIf(String::isNotBlank)?.let(LocalDate::parse) } catch (_: DateTimeParseException) { null }
