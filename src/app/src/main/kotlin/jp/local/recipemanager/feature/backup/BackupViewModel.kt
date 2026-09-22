package jp.local.recipemanager.feature.backup

import android.content.Context
import androidx.lifecycle.*
import jp.local.recipemanager.BuildConfig
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.data.local.SettingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BackupUiState(val loading:Boolean=false,val exportBytes:ByteArray?=null,val exportName:String="",val preview:ParsedBackup?=null,val error:String?=null,val message:String?=null,val lastBackupAt:String?=null,val reminderDays:Int=30,val holidayVersion:String="-")

class BackupViewModel(private val app: RecipeManagerApplication) : ViewModel() {
    val state=MutableStateFlow(BackupUiState())
    private val codec=BackupCodec(app)
    init { refreshSettings() }
    fun prepareExport()=viewModelScope.launch { state.value=state.value.copy(loading=true,error=null); state.value=runCatching { withContext(Dispatchers.IO){
        val now=Instant.now(); val rows=app.database.backupDao().snapshot(); val version=app.database.systemDataDao().getMetadata("food_master_version") ?: "unknown"
        val bytes=codec.encode(rows,now,BuildConfig.VERSION_NAME,version); val name="recipe-manager-backup_${DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss").withZone(ZoneId.systemDefault()).format(now)}.json"
        state.value.copy(loading=false,exportBytes=bytes,exportName=name)
    }}.getOrElse{state.value.copy(loading=false,error="BAK-001: ${it.message}")} }
    fun exportCompleted()=viewModelScope.launch { val now=Instant.now().toString(); app.database.systemDataDao().putSetting(SettingEntity("lastBackupAt",now)); state.value=state.value.copy(exportBytes=null,message="バックアップを保存しました",lastBackupAt=now) }
    fun exportCancelled(){state.value=state.value.copy(exportBytes=null)}
    fun load(bytes:ByteArray)=viewModelScope.launch { state.value=state.value.copy(loading=true,error=null,preview=null); state.value=when(val result=withContext(Dispatchers.Default){codec.parse(bytes)}){is BackupParseResult.Success->state.value.copy(loading=false,preview=result.backup);is BackupParseResult.Failure->state.value.copy(loading=false,error=result.message)} }
    fun restore()=viewModelScope.launch { val p=state.value.preview?:return@launch; state.value=state.value.copy(loading=true); state.value=runCatching{withContext(Dispatchers.IO){app.database.backupDao().replace(p.rows,p.createdAt)};state.value.copy(loading=false,preview=null,message="復元しました：${p.counts.entries.joinToString{ "${it.key}${it.value}件" }}",lastBackupAt=p.createdAt.toString())}.getOrElse{state.value.copy(loading=false,error="DB-001: ${it.message}")} }
    fun clearPreview(){state.value=state.value.copy(preview=null)}
    fun setReminder(days:Int)=viewModelScope.launch { app.database.systemDataDao().putSetting(SettingEntity("backupReminderIntervalDays",days.toString()));state.value=state.value.copy(reminderDays=days,message="通知間隔を保存しました") }
    private fun refreshSettings()=viewModelScope.launch { val dao=app.database.systemDataDao();state.value=state.value.copy(lastBackupAt=dao.getSetting("lastBackupAt")?.takeIf(String::isNotBlank),reminderDays=dao.getSetting("backupReminderIntervalDays")?.toIntOrNull()?:30,holidayVersion=dao.getMetadata("holiday_data_version")?:"-") }
    fun databaseBytes(context:Context):Long { val f=context.getDatabasePath(jp.local.recipemanager.data.local.RecipeManagerDatabase.DATABASE_NAME);return listOf(f,java.io.File(f.path+"-wal"),java.io.File(f.path+"-shm")).sumOf{if(it.exists())it.length()else 0} }
    companion object { fun factory(app:RecipeManagerApplication)=object:ViewModelProvider.Factory{@Suppress("UNCHECKED_CAST")override fun<T:ViewModel>create(modelClass:Class<T>):T=BackupViewModel(app) as T} }
}
