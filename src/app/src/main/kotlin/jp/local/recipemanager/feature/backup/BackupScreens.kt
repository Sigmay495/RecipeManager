package jp.local.recipemanager.feature.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

@Composable fun HomeScreen(vm:BackupViewModel,onMenu:()->Unit,onBackup:()->Unit){val s by vm.state.collectAsStateWithLifecycle();val overdue=s.lastBackupAt?.let{runCatching{ChronoUnit.DAYS.between(Instant.parse(it),Instant.now())>=s.reminderDays}.getOrDefault(true)}?:true;Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center){Text("レシピ管理",style=MaterialTheme.typography.headlineMedium);Text("2人分の平日献立を管理します。",Modifier.padding(vertical=12.dp));if(overdue)Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.errorContainer)){Column(Modifier.padding(14.dp)){Text("バックアップをおすすめします");TextButton(onBackup){Text("データ管理を開く")}}};Button(onMenu,Modifier.fillMaxWidth().padding(top=16.dp)){Text("献立を開く")}}}

@Composable fun DataManagementScreen(vm:BackupViewModel,onBack:()->Unit){
    val state by vm.state.collectAsStateWithLifecycle();val context=LocalContext.current;val scope=rememberCoroutineScope();var restoreConfirm by remember{mutableStateOf(false)}
    val create=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")){uri->if(uri!=null)scope.launch{runCatching{withContext(Dispatchers.IO){context.contentResolver.openOutputStream(uri,"w")!!.use{it.write(state.exportBytes!!)}}}.onSuccess{vm.exportCompleted()}.onFailure{vm.exportCancelled()}}else vm.exportCancelled()}
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)scope.launch{runCatching{withContext(Dispatchers.IO){context.readBackup(uri)}}.onSuccess(vm::load).onFailure{vm.load("{".toByteArray())}}}
    LaunchedEffect(state.exportBytes){if(state.exportBytes!=null)create.launch(state.exportName)}
    Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){Text("データ管理",style=MaterialTheme.typography.headlineSmall);TextButton(onBack){Text("戻る")};Text("使用量：約 ${vm.databaseBytes(context)/1024} KB");Text("最終バックアップ：${state.lastBackupAt?:"未実施"}");Text("データ消去やアンインストールの前にバックアップしてください。");Button(vm::prepareExport,enabled=!state.loading,modifier=Modifier.fillMaxWidth()){Text("バックアップ")};OutlinedButton({open.launch(arrayOf("application/json","text/json"))},enabled=!state.loading,modifier=Modifier.fillMaxWidth()){Text("復元ファイルを選択")};state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)};state.message?.let{Text(it,color=MaterialTheme.colorScheme.primary)};if(state.loading)CircularProgressIndicator()}
    state.preview?.let{p->AlertDialog({vm.clearPreview()},{Button({restoreConfirm=true}){Text("復元へ進む")}},dismissButton={TextButton(vm::clearPreview){Text("キャンセル")}},title={Text("復元内容の確認")},text={Text("現在の全データを置き換えます。先に現在データをバックアップしてください。\n${p.counts.entries.joinToString("\n"){"${it.key}: ${it.value}件"}}")})}
    if(restoreConfirm)AlertDialog({restoreConfirm=false},{Button({restoreConfirm=false;vm.restore()}){Text("全件置換して復元")}},dismissButton={TextButton({restoreConfirm=false}){Text("戻る")}},title={Text("本当に復元しますか？")},text={Text("この操作は元に戻せません。現在データのバックアップを確認してください。")})
}

@Composable fun SettingsScreen(vm:BackupViewModel,onBack:()->Unit){val s by vm.state.collectAsStateWithLifecycle();Column(Modifier.fillMaxSize().padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("設定",style=MaterialTheme.typography.headlineSmall);TextButton(onBack){Text("戻る")};Text("バックアップ通知間隔");listOf(7,14,30,60).forEach{d->FilterChip(s.reminderDays==d,{vm.setReminder(d)},{Text("$d 日")})};HorizontalDivider();Text("最終バックアップ：${s.lastBackupAt?:"未実施"}");Text("祝日データ：${s.holidayVersion}");Text("DB Schema：1");Text("バックアップ形式：1.0");Text("アプリ版：${jp.local.recipemanager.BuildConfig.VERSION_NAME}")}}

private fun android.content.Context.readBackup(uri:Uri):ByteArray{contentResolver.openAssetFileDescriptor(uri,"r")?.use{if(it.length>50L*1024*1024)error("ファイルサイズは50MB以内にしてください")};return contentResolver.openInputStream(uri)!!.use{it.readBytes()}}
