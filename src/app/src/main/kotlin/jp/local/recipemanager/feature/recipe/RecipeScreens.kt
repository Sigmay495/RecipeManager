@file:OptIn(ExperimentalMaterial3Api::class)

package jp.local.recipemanager.feature.recipe

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import jp.local.recipemanager.data.local.RecipeWithIngredients
import jp.local.recipemanager.domain.model.EffortLevel
import jp.local.recipemanager.domain.model.FoodGroup
import jp.local.recipemanager.domain.model.RecipeCategory
import jp.local.recipemanager.domain.model.RecipeStatus
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun RecipeListScreen(viewModel: RecipeViewModel, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val recipes by viewModel.recipes.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("レシピ") }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onAdd, text = { Text("新規登録") }, icon = { Text("＋") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = filters.query,
                onValueChange = { value -> viewModel.updateFilters { it.copy(query = value) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                label = { Text("料理名・材料名で検索") },
                singleLine = true,
            )
            FilterRow(filters, viewModel::updateFilters)
            Text(
                text = if (recipes.isEmpty()) "該当するレシピはありません" else "${recipes.size}件",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 88.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recipes, key = { it.recipe.id }) { record -> RecipeCard(record) { onOpen(record.recipe.id) } }
            }
        }
    }
}

@Composable
private fun FilterRow(filters: RecipeFilters, update: ((RecipeFilters) -> RecipeFilters) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filters.category == null, { update { it.copy(category = null) } }, { Text("全カテゴリ") })
            RecipeCategory.entries.forEach { category ->
                FilterChip(filters.category == category, { update { it.copy(category = category) } }, { Text(category.displayName()) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filters.status == RecipeStatus.ACTIVE, { update { it.copy(status = RecipeStatus.ACTIVE) } }, { Text("利用中") })
            FilterChip(filters.status == RecipeStatus.DELETED, { update { it.copy(status = RecipeStatus.DELETED) } }, { Text("削除済み") })
            listOf(null to "時間指定なし", 15 to "15分以内", 30 to "30分以内", 60 to "60分以内").forEach { (minutes, label) ->
                FilterChip(filters.maximumMinutes == minutes, { update { it.copy(maximumMinutes = minutes) } }, { Text(label) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(filters.effortLevel == null, { update { it.copy(effortLevel = null) } }, { Text("全手間") })
            EffortLevel.entries.forEach { effort -> FilterChip(filters.effortLevel == effort, { update { it.copy(effortLevel = effort) } }, { Text(effort.displayName()) }) }
            RecipeSort.entries.forEach { sort ->
                val label = when (sort) { RecipeSort.NAME -> "料理名順"; RecipeSort.UPDATED_AT -> "更新順"; RecipeSort.LAST_COOKED_AT -> "調理日順" }
                FilterChip(filters.sort == sort, { update { it.copy(sort = sort) } }, { Text(label) })
            }
        }
    }
}

@Composable
private fun RecipeCard(record: RecipeWithIngredients, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(record.recipe.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("${record.recipe.category.displayName()}　手間：${record.recipe.effortLevel.displayName()}　時間：${record.recipe.cookingTimeMinutes?.let { "${it}分" } ?: "未設定"}")
            Text("最終調理日：${record.recipe.lastCookedAt.displayText()}", style = MaterialTheme.typography.bodySmall)
            Text("栄養目安：${record.recipe.nutritionGroups.displayNutrition()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RecipeDetailScreen(recipeId: String, viewModel: RecipeViewModel, onBack: () -> Unit, onEdit: (String) -> Unit) {
    val record by viewModel.observeRecipe(recipeId).collectAsStateWithLifecycle(initialValue = null)
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Scaffold(topBar = { TopAppBar(title = { Text("レシピ詳細") }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) }) { padding ->
        val value = record
        if (value == null) Box(Modifier.fillMaxSize().padding(padding)) { Text("レシピが見つかりません", Modifier.padding(24.dp)) }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(value.recipe.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            item { DetailLine("カテゴリ", value.recipe.category.displayName()) }
            item { DetailLine("調理時間", value.recipe.cookingTimeMinutes?.let { "${it}分" } ?: "未設定") }
            item { DetailLine("手間", value.recipe.effortLevel.displayName()) }
            item { DetailLine("最終調理日", value.recipe.lastCookedAt.displayText()) }
            item { DetailLine("栄養目安", value.recipe.nutritionGroups.displayNutrition()) }
            item { Text("材料", fontWeight = FontWeight.Bold) }
            items(value.ingredients.sortedBy { it.displayOrder }) { ingredient ->
                Text("・${ingredient.name} ${ingredient.amount?.toPlainString().orEmpty()}${ingredient.unit}${ingredient.note.takeIf(String::isNotBlank)?.let { "（$it）" }.orEmpty()}")
            }
            if (value.recipe.sourceUrl != null) item {
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value.recipe.sourceUrl))) }) { Text("作り方URLを開く") }
            }
            if (value.recipe.memo.isNotBlank()) item { DetailLine("メモ", value.recipe.memo) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (value.recipe.status == RecipeStatus.ACTIVE) {
                        Button(onClick = { onEdit(value.recipe.id) }) { Text("編集") }
                        OutlinedButton(onClick = { confirmDelete = true }) { Text("削除") }
                    } else {
                        Button(onClick = { viewModel.restore(value.recipe.id) }) { Text("復元") }
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("レシピを削除しますか？") },
        text = { Text("データは論理削除され、削除済み検索から復元できます。") },
        confirmButton = { TextButton(onClick = { viewModel.delete(recipeId); confirmDelete = false }) { Text("削除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") } },
    )
}

@Composable private fun DetailLine(label: String, value: String) { Column { Text(label, fontWeight = FontWeight.Bold); Text(value) } }

@Composable
fun RecipeEditScreen(recipeId: String?, viewModel: RecipeViewModel, onBack: () -> Unit, onSaved: (String) -> Unit) {
    var draft by remember(recipeId) { mutableStateOf(RecipeDraft(id = recipeId)) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var duplicateWarning by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(recipeId) { if (recipeId != null) viewModel.loadDraft(recipeId)?.let { draft = it } }

    fun validateAndSave(confirmed: Boolean = false) {
        scope.launch {
            val validation = viewModel.validate(draft)
            errors = validation.fieldErrors
            if (!validation.isValid) return@launch
            if (!confirmed && validation.duplicateWarning != null) { duplicateWarning = validation.duplicateWarning; return@launch }
            saving = true
            runCatching { viewModel.save(draft) }
                .onSuccess { id -> withContext(Dispatchers.Main.immediate) { onSaved(id) } }
                .onFailure { errors = mapOf("save" to (it.message ?: "保存に失敗しました")) }
            saving = false
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (recipeId == null) "レシピ新規登録" else "レシピ編集") }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { InputField(draft.name, { draft = draft.copy(name = it) }, "料理名（必須）", errors["name"]) }
            item { Text("カテゴリ", fontWeight = FontWeight.Bold); ChoiceRow(RecipeCategory.entries, draft.category, { draft = draft.copy(category = it) }) { it.displayName() } }
            item { Text("材料", fontWeight = FontWeight.Bold); errors["ingredients"]?.let { ErrorText(it) } }
            items(draft.ingredients.size) { index ->
                IngredientEditor(
                    index = index,
                    value = draft.ingredients[index],
                    errors = errors,
                    canRemove = draft.ingredients.size > 1,
                    onChange = { updated -> draft = draft.copy(ingredients = draft.ingredients.toMutableList().also { it[index] = updated }) },
                    onRemove = { draft = draft.copy(ingredients = draft.ingredients.toMutableList().also { it.removeAt(index) }) },
                    onMoveUp = if (index > 0) {{ draft = draft.copy(ingredients = draft.ingredients.toMutableList().also { list -> val item = list.removeAt(index); list.add(index - 1, item) }) }} else null,
                    onMoveDown = if (index < draft.ingredients.lastIndex) {{ draft = draft.copy(ingredients = draft.ingredients.toMutableList().also { list -> val item = list.removeAt(index); list.add(index + 1, item) }) }} else null,
                )
            }
            item { OutlinedButton(onClick = { draft = draft.copy(ingredients = draft.ingredients + IngredientDraft()) }) { Text("材料を追加") } }
            item { InputField(draft.cookingTime, { draft = draft.copy(cookingTime = it) }, "調理時間（分）", errors["cookingTime"], KeyboardType.Number) }
            item { Text("手間", fontWeight = FontWeight.Bold); ChoiceRow(EffortLevel.entries, draft.effortLevel, { draft = draft.copy(effortLevel = it) }) { it.displayName() } }
            if (recipeId == null) item { InputField(draft.initialLastCookedAt, { draft = draft.copy(initialLastCookedAt = it) }, "最終調理日（YYYY-MM-DD）", errors["initialLastCookedAt"]) }
            item { InputField(draft.sourceUrl, { draft = draft.copy(sourceUrl = it) }, "作り方URL", errors["sourceUrl"], KeyboardType.Uri) }
            item { InputField(draft.memo, { draft = draft.copy(memo = it) }, "メモ", errors["memo"], singleLine = false) }
            errors["save"]?.let { message -> item { ErrorText(message) } }
            item { Button(onClick = { validateAndSave() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) { Text(if (saving) "保存中…" else "保存") } }
        }
    }
    duplicateWarning?.let { warning -> AlertDialog(
        onDismissRequest = { duplicateWarning = null }, title = { Text("重複候補があります") }, text = { Text(warning) },
        confirmButton = { TextButton(onClick = { duplicateWarning = null; validateAndSave(true) }) { Text("保存する") } },
        dismissButton = { TextButton(onClick = { duplicateWarning = null }) { Text("戻る") } },
    ) }
}

@Composable
private fun IngredientEditor(index: Int, value: IngredientDraft, errors: Map<String, String>, canRemove: Boolean, onChange: (IngredientDraft) -> Unit, onRemove: () -> Unit, onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InputField(value.name, { onChange(value.copy(name = it)) }, "材料名", errors["ingredient.$index.name"])
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { InputField(value.amount, { onChange(value.copy(amount = it)) }, "分量", errors["ingredient.$index.amount"], KeyboardType.Decimal) }
            Box(Modifier.weight(1f)) { InputField(value.unit, { onChange(value.copy(unit = it)) }, "単位", errors["ingredient.$index.unit"]) }
        }
        InputField(value.note, { onChange(value.copy(note = it)) }, "注記", null)
        Row { TextButton(onClick = { onMoveUp?.invoke() }, enabled = onMoveUp != null) { Text("↑") }; TextButton(onClick = { onMoveDown?.invoke() }, enabled = onMoveDown != null) { Text("↓") }; if (canRemove) TextButton(onClick = onRemove) { Text("削除") } }
    } }
}

@Composable private fun InputField(value: String, onChange: (String) -> Unit, label: String, error: String?, keyboardType: KeyboardType = KeyboardType.Text, singleLine: Boolean = true) {
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, isError = error != null, supportingText = error?.let { { ErrorText(it) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboardType), singleLine = singleLine)
}

@Composable private fun <T> ChoiceRow(values: List<T>, selected: T, onSelect: (T) -> Unit, label: (T) -> String) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { values.forEach { FilterChip(selected == it, { onSelect(it) }, { Text(label(it)) }) } }
}

@Composable private fun ErrorText(message: String) { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

private fun Set<FoodGroup>.displayNutrition(): String = if (isEmpty()) "未判定" else joinToString("・") { group ->
    when (group) {
        FoodGroup.GRAIN -> "穀類"; FoodGroup.MEAT -> "肉"; FoodGroup.SEAFOOD -> "魚介"; FoodGroup.EGG -> "卵"
        FoodGroup.DAIRY -> "乳製品"; FoodGroup.SOY -> "大豆"; FoodGroup.VEGETABLE -> "野菜"; FoodGroup.MUSHROOM -> "きのこ"
        FoodGroup.SEAWEED -> "海藻"; FoodGroup.FRUIT -> "果物"; FoodGroup.FAT -> "油脂"; FoodGroup.SEASONING -> "調味料"
    }
}
