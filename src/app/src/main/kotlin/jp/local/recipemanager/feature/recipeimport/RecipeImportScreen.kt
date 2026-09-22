@file:OptIn(ExperimentalMaterial3Api::class)

package jp.local.recipemanager.feature.recipeimport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
fun RecipeImportScreen(viewModel: RecipeImportViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.readImport(uri) } }
                .onSuccess { (name, bytes) -> viewModel.load(name, bytes) }
                .onFailure { viewModel.fail(it.message ?: "ファイルを読み込めませんでした") }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("レシピImport") }, navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } }) }) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding)) { CircularProgressIndicator(Modifier.padding(32.dp)) }
            state.result != null -> ImportResultContent(state.result!!, viewModel::reset, Modifier.padding(padding))
            else -> ImportPreviewContent(
                state = state,
                onChooseFile = { launcher.launch(arrayOf("application/json", "text/json")) },
                onSelect = viewModel::select,
                onExecute = viewModel::execute,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@Composable
private fun ImportPreviewContent(
    state: RecipeImportUiState,
    onChooseFile: () -> Unit,
    onSelect: (Int, ImportAction, String?) -> Unit,
    onExecute: () -> Unit,
    modifier: Modifier,
) {
    val preview = state.preview
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChooseFile) { Text(if (preview == null) "JSONファイルを選択" else "別のファイルを選択") }
        }
        state.fatalError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
        if (preview == null) {
            Text("ChatGPTなどで作成したrecipe-import形式のJSONを選択してください。最大5MB・100件です。", Modifier.padding(16.dp))
        } else {
            Text("${preview.fileName}　正常 ${preview.validCount}件／エラー ${preview.errorCount}件", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(preview.candidates, key = { it.index }) { candidate -> ImportCandidateCard(candidate, onSelect) }
            }
            val executable = preview.candidates.any { it.action == ImportAction.ADD || it.action == ImportAction.RESTORE }
            Button(onClick = onExecute, enabled = executable, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("選択内容をImport") }
        }
    }
}

@Composable
private fun ImportCandidateCard(candidate: RecipeImportCandidate, onSelect: (Int, ImportAction, String?) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(candidate.displayName, fontWeight = FontWeight.Bold)
            candidate.draft?.let { Text("${it.category}　材料${it.ingredients.size}件") }
            candidate.sourceWarnings.forEach { Text("注意: $it", color = MaterialTheme.colorScheme.tertiary) }
            candidate.errors.forEach { Text("エラー: $it", color = MaterialTheme.colorScheme.error) }
            candidate.duplicates.forEach { duplicate ->
                Text("重複候補: ${duplicate.name}（${duplicate.reason}${if (duplicate.deleted) "・削除済み" else ""}）", color = MaterialTheme.colorScheme.tertiary)
            }
            if (candidate.errors.isEmpty()) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(candidate.action == ImportAction.ADD, { onSelect(candidate.index, ImportAction.ADD, null) }, { Text("追加") })
                    candidate.duplicates.filter(ImportDuplicate::deleted).forEach { duplicate ->
                        FilterChip(
                            candidate.action == ImportAction.RESTORE && candidate.restoreRecipeId == duplicate.recipeId,
                            { onSelect(candidate.index, ImportAction.RESTORE, duplicate.recipeId) },
                            { Text("${duplicate.name}を復元") },
                        )
                    }
                    FilterChip(candidate.action == ImportAction.SKIP, { onSelect(candidate.index, ImportAction.SKIP, null) }, { Text("スキップ") })
                }
            }
        }
    }
}

@Composable
private fun ImportResultContent(result: RecipeImportResult, onReset: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Importが完了しました", style = MaterialTheme.typography.headlineSmall)
        Text("追加 ${result.added}件\n復元 ${result.restored}件\nスキップ ${result.skipped}件\nエラー ${result.errors}件")
        Button(onClick = onReset) { Text("別のファイルをImport") }
    }
}

private fun Context.readImport(uri: Uri): Pair<String, ByteArray> {
    var displayName = "recipe-import.json"
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { displayName = cursor.getString(it) }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let {
                require(cursor.getLong(it) <= RecipeImportParser.MAX_FILE_BYTES) { "ファイルサイズは5MB以内にしてください" }
            }
        }
    }
    val output = ByteArrayOutputStream()
    contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "ファイルを開けませんでした" }
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= RecipeImportParser.MAX_FILE_BYTES) { "ファイルサイズは5MB以内にしてください" }
        }
    }
    return displayName to output.toByteArray()
}
