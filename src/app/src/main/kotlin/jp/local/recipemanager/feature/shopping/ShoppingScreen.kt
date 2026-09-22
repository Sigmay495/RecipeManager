package jp.local.recipemanager.feature.shopping

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.local.recipemanager.data.local.ShoppingItemEntity

@Composable
fun ShoppingScreen(viewModel: ShoppingViewModel) {
    val start by viewModel.weekStart.collectAsState()
    val list by viewModel.list.collectAsState()
    val menuUpdatedAt by viewModel.menuUpdatedAt.collectAsState()
    val message by viewModel.message.collectAsState()
    val stale = list != null && menuUpdatedAt != null && menuUpdatedAt!! > list!!.list.sourceMenuUpdatedAt
    LaunchedEffect(start, list) { viewModel.refresh() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("買い物材料リスト", style = MaterialTheme.typography.headlineSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ viewModel.moveWeek(-7) }) { Text("前の週") }; Text(start.toString(), Modifier.padding(top = 12.dp)); TextButton({ viewModel.moveWeek(7) }) { Text("次の週") }
            }
            if (stale) Text("⚠ 献立が変更されています。再生成してください。", color = MaterialTheme.colorScheme.error)
            Button(viewModel::generate, Modifier.fillMaxWidth()) { Text(if (list == null) "献立から生成" else "献立から再生成") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (list == null) Text("この週の買い物リストはまだありません。")
        }
        items(list?.items.orEmpty(), key = ShoppingItemEntity::id) { item -> ShoppingRow(item, viewModel) }
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItemEntity, viewModel: ShoppingViewModel) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (item.excluded) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(12.dp)) {
            Row { Checkbox(item.checked, { viewModel.toggleChecked(item) }, enabled = !item.excluded); Column(Modifier.padding(top = 10.dp)) {
                Text("${item.name}  ${item.amount?.toPlainString() ?: "分量不明"}${item.unit}", fontWeight = FontWeight.Bold)
                Text("使用料理：${item.recipeNames}", style = MaterialTheme.typography.bodySmall)
            } }
            Row { Checkbox(item.excluded, { viewModel.toggleExcluded(item) }); Text("購入不要", Modifier.padding(top = 12.dp)) }
        }
    }
}
