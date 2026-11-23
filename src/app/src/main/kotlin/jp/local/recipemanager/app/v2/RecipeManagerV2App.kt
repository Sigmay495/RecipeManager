package jp.local.recipemanager.app.v2

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.*
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.data.local.v2.*
import jp.local.recipemanager.domain.model.FoodGroup
import jp.local.recipemanager.core.text.TextNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.Instant
import java.time.temporal.ChronoUnit

private val LocalUiBusy = compositionLocalOf { false }
private val jsonDocumentMimeTypes = arrayOf("application/json", "application/octet-stream", "text/*")
val categoriesV2 = linkedMapOf("one_dish" to "一品もの","main" to "主菜","side" to "副菜","soup" to "汁物")
val prioritiesV2 = linkedMapOf("high" to "高","normal" to "普通","low" to "低","retired" to "廃止")
private val efforts = linkedMapOf("easy" to "簡単","normal" to "普通","hard" to "手間")
private val groupNames = mapOf("GRAIN" to "穀類","MEAT" to "肉","SEAFOOD" to "魚介","EGG" to "卵","DAIRY" to "乳製品","SOY" to "大豆","VEGETABLE" to "野菜","MUSHROOM" to "きのこ","SEAWEED" to "海藻","FRUIT" to "果物","FAT" to "油脂","SEASONING" to "調味料")
private fun labelRole(value: String) = categoriesV2[value.lowercase()] ?: value
private fun name(state: AppStateV2,id: String) = state.rows?.recipes?.find { it.id == id }?.name ?: "料理が見つかりません"

@Composable fun RecipeManagerV2App() {
    val application = LocalContext.current.applicationContext as Application
    val vm: AppViewModelV2 = viewModel(factory=object: ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = AppViewModelV2(application) as T
    })
    val state by vm.state.collectAsState()
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle,vm) {
        val observer = LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) vm.flushDraft() }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer); vm.flushDraft() }
    }
    BackHandler(state.route!="home") { vm.navigate(parent(state.route)) }
    CompositionLocalProvider(LocalUiBusy provides state.busy) {
        Scaffold(bottomBar={
            if(state.ready) NavigationV2(state.route) { vm.navigate(it) }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if(state.error!=null) NoticeV2(state.error!!,true,vm::clearNotice)
                if(state.message!=null) NoticeV2(state.message!!,false,vm::clearNotice)
                if(state.undoAvailable) Btn("不要化を取り消す") { vm.undoNeed() }
                if(!state.ready) Page("データを準備しています") {
                    Text("既存データを保持して読み込んでいます。")
                    if(state.error!=null) Btn("再試行") { vm.retryInitialization() }
                } else key(state.route) {
                    when {
                        state.route=="home" -> HomeV2(vm,state)
                        state.route=="menu" -> MenuV2(vm,state)
                        state.route=="day" -> DayV2(vm,state)
                        state.route=="recipes" -> RecipesV2(vm,state)
                        state.route.startsWith("recipe/") -> RecipeDetailV2(vm,state,state.route.substringAfter('/'))
                        state.route=="recipeEdit" -> RecipeEditorV2(vm,state)
                        state.route=="foods" -> FoodsV2(vm,state)
                        state.route.startsWith("food/") -> FoodEditorV2(vm,state,state.route.substringAfter('/').takeUnless { it=="new" })
                        state.route=="shopping" -> ShoppingV2(vm,state)
                        state.route=="history" -> HistoryV2(vm,state)
                        state.route.startsWith("historyEdit/") -> HistoryEditorV2(vm,state,state.route.substringAfter('/').takeUnless { it=="new" })
                        state.route=="proposal" -> ProposalV2(vm,state)
                        state.route=="import" -> ImportV2(vm,state)
                        state.route=="backup" -> BackupV2(vm,state)
                        state.route=="settings" -> SettingsV2(vm,state)
                        state.route=="drafts" -> DraftsV2(vm,state)
                        else -> Page("その他") {
                            Btn("買い物") { vm.navigate("shopping") }; Btn("調理履歴") { vm.navigate("history") }
                            Btn("レシピImport") { vm.navigate("import") }; Btn("保存中の下書き") { vm.navigate("drafts") }
                            Btn("データ管理") { vm.navigate("backup") }; Btn("設定") { vm.navigate("settings") }
                        }
                    }
                }
            }
        }
        if(state.confirmTitle!=null) ReviewDialogV2(state.confirmTitle!!,vm::dismissConfirmation) {
            Text(state.confirmMessage)
            Btn("確認して実行",primary=true) { vm.confirm() }; Btn("取り消す") { vm.dismissConfirmation() }
        }
    }
}
private fun parent(route:String)=when {
    route=="recipeEdit"||route.startsWith("recipe/")->"recipes"
    route.startsWith("food/")->"foods"
    route=="day"||route=="proposal"||route=="shopping"->"menu"
    route.startsWith("historyEdit/")->"history"
    route in setOf("history", "import", "backup", "settings", "drafts")->"more"
    else->"home"
}
@Composable private fun NavigationV2(route:String,onNavigate:(String)->Unit) {
    val entries=listOf("home" to "ホーム","menu" to "献立","recipes" to "レシピ","foods" to "材料","more" to "その他")
    val currentTab = when {
        entries.any { it.first == route } -> route
        route.startsWith("historyEdit/") -> "more"
        else -> parent(route)
    }
    Surface(color=MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(4.dp)
            .padding(bottom=if(LocalDensity.current.fontScale>=1.5f) 24.dp else 4.dp),verticalAlignment=Alignment.CenterVertically) {
            entries.forEachIndexed { index,entry ->
                val selected=entry.first==currentTab
                Column(Modifier.weight(1f).heightIn(min=72.dp).background(if(selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(16.dp))
                    .clickable(enabled=!LocalUiBusy.current,role=Role.Tab) { onNavigate(entry.first) }
                    .semantics(mergeDescendants=true) { this.selected=selected }
                    .padding(horizontal=3.dp,vertical=8.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                    NavigationIconV2(index,selected)
                    Text(entry.second,style=MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
@Composable private fun NavigationIconV2(index:Int,selected:Boolean) {
    val color=if(selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(24.dp).clearAndSetSemantics {}) {
        val unit=size.width/24f
        val stroke=Stroke(1.7f*unit)
        fun point(x:Float,y:Float)=Offset(x*unit,y*unit)
        when(index) {
            0->{
                drawPath(Path().apply { moveTo(3*unit,11*unit);lineTo(12*unit,3*unit);lineTo(21*unit,11*unit) },color,style=stroke)
                drawRect(color,point(6f,11f),Size(12*unit,10*unit),style=stroke)
            }
            1->{drawRect(color,point(3f,4f),Size(18*unit,17*unit),style=stroke);drawLine(color,point(3f,9f),point(21f,9f),strokeWidth=1.7f*unit)}
            2->{drawRect(color,point(4f,2f),Size(16*unit,20*unit),style=stroke);listOf(8f,12f,16f).forEach { y->drawLine(color,point(8f,y),point(16f,y),strokeWidth=1.5f*unit) }}
            3->drawPath(Path().apply { moveTo(12*unit,2*unit);lineTo(22*unit,12*unit);lineTo(12*unit,22*unit);lineTo(2*unit,12*unit);close() },color,style=stroke)
            else->listOf(5f,12f,19f).forEach { x->drawCircle(color,2*unit,point(x,12f)) }
        }
    }
}
@Composable fun Page(title:String,back:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        if(back!=null) Btn("戻る",onClick=back)
        Text(title,style=MaterialTheme.typography.titleLarge,modifier=Modifier.semantics { heading() })
        content()
        Spacer(Modifier.height(24.dp))
    }
}
@Composable fun Btn(text:String,primary:Boolean=false,enabled:Boolean=true,onClick:()->Unit) {
    if(primary) Button(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled&&!LocalUiBusy.current) { Text(text) }
    else OutlinedButton(onClick,Modifier.fillMaxWidth().heightIn(min=48.dp),enabled=enabled&&!LocalUiBusy.current) { Text(text) }
}
@Composable fun Field(label:String,value:String,onChange:(String)->Unit,multiline:Boolean=false,enabled:Boolean=true) {
    OutlinedTextField(value,onChange,Modifier.fillMaxWidth(),label={Text(label)},singleLine=!multiline,enabled=enabled&&!LocalUiBusy.current,minLines=if(multiline) 3 else 1)
}
@Composable fun Check(label:String,value:Boolean,onChange:(Boolean)->Unit,enabled:Boolean=true) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value,enabled=enabled&&!LocalUiBusy.current,role=Role.Checkbox,onValueChange=onChange).padding(vertical=4.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Checkbox(value,null,enabled=enabled); Text(label,Modifier.padding(start=8.dp).weight(1f))
    }
}
@Composable fun InfoCard(title:String,content:@Composable ColumnScope.()->Unit) {
    Surface(color=MaterialTheme.colorScheme.surfaceVariant,shape=RoundedCornerShape(20.dp),modifier=Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(title,style=MaterialTheme.typography.titleMedium); content()
        }
    }
}
@Composable private fun NoticeV2(text:String,error:Boolean,onDismiss:()->Unit) {
    Surface(color=if(error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().heightIn(max=200.dp).verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(text,Modifier.semantics { liveRegion=LiveRegionMode.Polite })
            TextButton(onDismiss,Modifier.heightIn(min=48.dp)) { Text("閉じる") }
        }
    }
}
@Composable fun ReviewDialogV2(title:String,onDismiss:()->Unit,content:@Composable ColumnScope.()->Unit) {
    Dialog(onDismissRequest=onDismiss) {
        Surface(shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max=600.dp).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(title,style=MaterialTheme.typography.titleLarge); content()
            }
        }
    }
}
@Composable fun Choice(label:String,value:String,options:Map<String,String>,onChange:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    Btn("$label：${options[value] ?: value}") { open=true }
    if(open) ReviewDialogV2(label,{open=false}) {
        options.forEach { (key,text)->Btn(text) { onChange(key); open=false } }
        Btn("取り消す") { open=false }
    }
}
@Composable fun RecipePicker(state:AppStateV2,title:String,category:String?=null,all:Boolean=false,onDismiss:()->Unit,onSelect:(RecipeEntityV2)->Unit) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest=onDismiss) {
        Surface(shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max=600.dp).padding(16.dp)) {
                Text(title,style=MaterialTheme.typography.titleMedium)
                Field("料理名で検索",query,{query=it})
                LazyColumn(Modifier.weight(1f,false)) {
                    val rows=state.rows!!.recipes.filter { (all||it.proposalPriority!="retired")&&(category==null||it.category.lowercase()==category)&&it.normalizedName.contains(TextNormalizer.normalize(query)) }
                    if(rows.isEmpty()) item { Text("該当する料理がありません。レシピから登録してください。") }
                    items(rows,key={it.id}) { recipe->Btn("${recipe.name}・${labelRole(recipe.category)}・優先度${prioritiesV2[recipe.proposalPriority]}") { onSelect(recipe) } }
                }
                Btn("取り消す",onClick=onDismiss)
            }
        }
    }
}
@Composable fun FoodPicker(state:AppStateV2,onDismiss:()->Unit,onSelect:(FoodMasterEntityV2)->Unit,onNew:((String)->Unit)?=null,exclude:String?=null) {
    var query by remember { mutableStateOf("") }
    Dialog(onDismissRequest=onDismiss) {
        Surface(shape=RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max=650.dp).padding(16.dp)) {
                Text("材料を検索",style=MaterialTheme.typography.titleMedium)
                Field("材料名・別名",query,{query=it})
                LazyColumn(Modifier.weight(1f,false)) {
                    val normalized=TextNormalizer.normalize(query)
                    val aliases=(state.rows!!.builtInAliases.map { it.normalizedAlias to it.foodMasterId }+state.rows.userAliases.map { it.normalizedAlias to it.foodMasterId }).filter { it.first==normalized }.map { it.second }.toSet()
                    items(state.rows.foods.filter { it.id!=exclude&&(it.normalizedName.contains(normalized)||it.id in aliases) },key={it.id}) { food->
                        Btn(food.name+if(food.userEditable) "（利用者材料）" else "（内蔵）") { onSelect(food) }
                    }
                }
                if(onNew!=null&&query.isNotBlank()) Btn("「$query」を新規材料候補にする") { onNew(query) }
                Btn("取り消す",onClick=onDismiss)
            }
        }
    }
}
@Composable private fun HomeV2(vm:AppViewModelV2,state:AppStateV2) {
    val today=LocalDate.now()
    val days=state.week!!.days
    Page("ホーム") {
        Text("${days.first().day.date} — ${days.last().day.date}")
        InfoCard("今週の夕食") {
            Text("必要 ${days.count { !it.day.noMeal }}日・未設定 ${days.count { !it.day.noMeal&&it.items.isEmpty() }}日")
            Btn("献立を確認",true) { vm.navigate("menu") }
        }
        days.forEach { day->InfoCard("${day.day.date}（${weekday(day.day.date)}）・${if(day.day.noMeal) "不要" else "必要"}") {
            Text(if(day.day.noMeal) "夕食不要" else day.items.joinToString("\n") { it.recipeNameSnapshot }.ifBlank { "献立未設定" })
            val outcomes=state.rows!!.outcomes.filter { row->day.items.any { it.id==row.menuItemId } }
            if(outcomes.isNotEmpty()) Text("結果記録 ${outcomes.size}件")
            Btn("この日の献立を開く") { vm.openDay(LocalDate.parse(day.day.date)) }
        } }
        val settings=state.rows!!.settings.associate { it.key to it.value }
        val last=settings["lastBackupAt"]?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if(last==null||ChronoUnit.DAYS.between(last,Instant.now())>=(settings["backupReminderIntervalDays"]?.toLongOrNull()?:30))
            InfoCard("バックアップのお知らせ") { Text("定期的に全データを保存してください。"); Btn("データ管理") { vm.navigate("backup") } }
        if(state.rows.drafts.isNotEmpty()) Btn("保存中の下書きを再開（${state.rows.drafts.size}件）") { vm.navigate("drafts") }
    }
}
private fun weekday(date:String)=listOf("月","火","水","木","金","土","日")[LocalDate.parse(date).dayOfWeek.value-1]
@Composable private fun WeekControls(vm:AppViewModelV2,state:AppStateV2) {
    Text("${state.week!!.days.first().day.date} — ${state.week.days.last().day.date}")
    Btn("前の週") { vm.changeWeek(-1) }; Btn("次の週") { vm.changeWeek(1) }
}
@Composable private fun MenuV2(vm:AppViewModelV2,state:AppStateV2) {
    Page("献立") {
        WeekControls(vm,state)
        Btn("1週間の献立を自動提案",true) { vm.generateProposal() }
        Btn("買い物") { vm.navigate("shopping") }
        Btn("料理を登録・候補を増やす") { vm.navigate("recipes") }
        val years=state.week!!.coveredHolidayYears
        Text("祝日情報の収録年：${years.sorted().joinToString().ifBlank { "未収録" }}",style=MaterialTheme.typography.bodySmall)
        if(state.week.days.any { LocalDate.parse(it.day.date).year !in years }) Text("この週には祝日未収録の日があります。要否を手動で確認してください。")
        state.week.days.forEach { day->
            InfoCard("${day.day.date}（${weekday(day.day.date)}）・${if(day.day.noMeal) "不要" else "必要"}") {
                if(day.day.dayLocked) Text("日全体を固定")
                if(!day.day.noMeal) Text(day.items.joinToString("\n") { "${labelRole(it.role)}：${it.recipeNameSnapshot}${if(it.locked) "（固定）" else ""}" }.ifBlank { "献立未設定" })
                Btn(if(day.day.noMeal) "この日を必要にする" else "この日を不要にする") { vm.toggleNeed(day) }
                Btn("料理・固定・結果を開く") { vm.openDay(LocalDate.parse(day.day.date)) }
            }
        }
    }
}
@Composable private fun DayV2(vm:AppViewModelV2,state:AppStateV2) {
    val day=state.week!!.days.single { it.day.date==state.date.toString() }
    val pending=state.pendingId?.let { id->state.rows!!.postponements.single { it.id==id } }
    var mode by remember(day.day.id,day.day.revision,pending?.id) { mutableStateOf(if(pending!=null) {
        if(state.rows!!.recipes.single { it.id==pending.recipeId }.category=="ONE_DISH") "one_dish" else "three"
    } else if(day.items.any { it.role=="ONE_DISH" }) "one_dish" else "three") }
    var selections by remember(day.day.id,day.day.revision,pending?.id) { mutableStateOf(day.items.associate { it.role.lowercase() to it.recipeId }.let { old ->
        if(pending==null) old else old + (state.rows!!.recipes.single { it.id==pending.recipeId }.category.lowercase() to pending.recipeId)
    }) }
    var locked by remember(day.day.id,day.day.revision) { mutableStateOf(day.day.dayLocked) }
    var itemLocks by remember(day.day.id,day.day.revision) { mutableStateOf(day.items.filter { it.locked }.map { it.role.lowercase() }.toSet()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var actual by remember(day.day.id,day.day.revision) { mutableStateOf(day.items.associate { it.id to it.recipeId as String? }) }
    var changedItem by remember { mutableStateOf<String?>(null) }
    var postponeDate by remember { mutableStateOf(state.date.plusDays(1).toString()) }
    Page("日別の献立・調理結果",{vm.navigate("menu")}) {
        Text("${day.day.date}（${weekday(day.day.date)}）・${if(day.day.noMeal) "不要" else "必要"}")
        if(pending!=null) Text("延期先の入替え確認：${name(state,pending.recipeId)}")
        if(day.day.noMeal) Btn("必要にする") { vm.toggleNeed(day) }
        Choice("献立構成",mode,mapOf("one_dish" to "一品もの","three" to "主菜・副菜・汁物"),{mode=it})
        val roles=if(mode=="one_dish") listOf("one_dish") else listOf("main","side","soup")
        roles.forEach { role->
            Btn("${categoriesV2[role]}：${selections[role]?.let { name(state,it) } ?: "未選択"}") { picker=role }
            Check("${categoriesV2[role]}を固定",role in itemLocks,{ itemLocks=if(it) itemLocks+role else itemLocks-role })
        }
        Check("日全体を固定",locked,{locked=it})
        Btn("献立・固定を保存",true) {
            vm.saveDay(MenuDayInputV2(state.date,false,roles.mapNotNull { role->selections[role]?.let { MenuSelectionV2(it,role.uppercase(),role in itemLocks) } },locked),day)
        }
        Btn("必要・献立未設定で保存") { vm.saveDay(MenuDayInputV2(state.date,false),day) }
        if(day.day.noMealReason.isNotBlank()||day.day.noMealMemo.isNotBlank()) InfoCard("旧理由・メモ") { Text(day.day.noMealReason); Text(day.day.noMealMemo) }
        if(day.items.isNotEmpty()) {
            Text("実際の調理結果",style=MaterialTheme.typography.titleMedium)
            day.items.forEach { item->
                val outcome=state.rows!!.outcomes.find { it.menuItemId==item.id }
                InfoCard("${labelRole(item.role)}：${item.recipeNameSnapshot}") {
                    if(outcome==null) {
                        Choice("結果",if(actual[item.id]==null) "none" else if(actual[item.id]==item.recipeId) "cooked" else "changed",
                            mapOf("cooked" to "作った","none" to "作らなかった","changed" to "別の料理を作った"),{ value->
                                if(value=="changed") changedItem=item.id
                                else actual=actual+(item.id to if(value=="none") null else item.recipeId)
                            })
                        if(actual[item.id]!=null&&actual[item.id]!=item.recipeId) Text("実際の料理：${name(state,actual[item.id]!!)}")
                        Field("延期先の日付（YYYY-MM-DD）",postponeDate,{postponeDate=it})
                        Btn("この料理を延期") { vm.postpone(day,item,postponeDate) }
                    } else {
                        Text("結果：${mapOf("cooked" to "作った","not_cooked" to "作らなかった","changed" to "変更","postponed" to "延期")[outcome.state]}")
                        if(outcome.state!="postponed") Btn("結果の関連を解除") { vm.clearOutcome(day,item) }
                        state.rows.postponements.filter { it.sourceMenuItemId==item.id }.forEach { plan->
                            Text("延期先 ${plan.targetDate}・${if(plan.state=="pending") "未確定" else "確定済み"}")
                            if(plan.state=="pending") Btn("延期先の料理を確認・入替え") { vm.openDay(LocalDate.parse(plan.targetDate),plan.id) }
                            Btn("延期を取り消す") { vm.cancelPostponement(plan) }
                        }
                    }
                }
            }
            Btn("未記録の調理結果を登録",enabled=day.items.all { item->state.rows!!.outcomes.none { it.menuItemId==item.id } }) { vm.record(day,actual) }
        }
    }
    if(picker!=null) RecipePicker(state,"${categoriesV2[picker]}を選択",picker,onDismiss={picker=null}) { selected->selections=selections+(picker!! to selected.id); picker=null }
    if(changedItem!=null) RecipePicker(state,"実際に作った料理",all=true,onDismiss={changedItem=null}) { selected->actual=actual+(changedItem!! to selected.id); changedItem=null }
}
@Composable private fun RecipesV2(vm:AppViewModelV2,state:AppStateV2) {
    var query by rememberSaveable { mutableStateOf("") }; var priority by rememberSaveable { mutableStateOf("all") }
    var category by rememberSaveable { mutableStateOf("all") }; var effort by rememberSaveable { mutableStateOf("all") }; var minutes by rememberSaveable { mutableStateOf("") }
    val normalized=TextNormalizer.normalize(query)
    val matchingFoods=state.rows!!.foods.filter { it.normalizedName.contains(normalized)||state.rows.userAliases.any { a->a.foodMasterId==it.id&&a.normalizedAlias==normalized }||state.rows.builtInAliases.any { a->a.foodMasterId==it.id&&a.normalizedAlias==normalized } }.map { it.id }.toSet()
    val matched=state.rows.recipes.filter { r->
        (priority=="all"||r.proposalPriority==priority)&&(category=="all"||r.category.lowercase()==category)&&(effort=="all"||r.effortLevel.lowercase()==effort)&&
        (minutes.toIntOrNull()==null||r.cookingTimeMinutes?.let { it<=minutes.toInt() }==true)&&
        (normalized.isEmpty()||r.normalizedName.contains(normalized)||state.rows.ingredients.any { i->i.recipeId==r.id&&(i.normalizedName.contains(normalized)||i.foodMasterId in matchingFoods) })
    }.sortedBy { it.normalizedName }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("レシピ",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=20.dp)); Field("料理名・材料名で検索",query,{query=it})
            Text("検索結果 ${matched.size}件")
            Btn("レシピを登録",true) { vm.openRecipe() }; Btn("レシピImport") { vm.navigate("import") }
            Choice("優先度",priority,mapOf("all" to "すべて")+prioritiesV2,{priority=it}); Choice("カテゴリ",category,mapOf("all" to "すべて")+categoriesV2,{category=it})
            Choice("手間",effort,mapOf("all" to "すべて")+efforts,{effort=it}); Field("調理時間の上限（分）",minutes,{minutes=it})
        }
        items(matched,key={it.id}) { recipe->InfoCard(recipe.name) {
            Text("${labelRole(recipe.category)}・優先度 ${prioritiesV2[recipe.proposalPriority]}・${recipe.cookingTimeMinutes?.let { "$it 分" } ?: "時間未設定"}")
            Text(recipe.lastCookedAt?.let { "最終調理日 $it" } ?: "調理履歴なし")
            Btn("「${recipe.name}」の詳細") { vm.navigate("recipe/${recipe.id}") }
        } }
        if(matched.isEmpty()) item { Text("該当するレシピがありません。登録や絞込条件を確認してください。") }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
@Composable private fun RecipeDetailV2(vm:AppViewModelV2,state:AppStateV2,id:String) {
    val recipe=state.rows!!.recipes.find { it.id==id } ?: return
    val uri=LocalUriHandler.current
    Page("レシピ詳細",{vm.navigate("recipes")}) {
        Text(recipe.name,style=MaterialTheme.typography.titleLarge)
        Text("${labelRole(recipe.category)}・優先度 ${prioritiesV2[recipe.proposalPriority]}・手間 ${efforts[recipe.effortLevel.lowercase()]}")
        Text(recipe.cookingTimeMinutes?.let { "調理時間 $it 分" } ?: "調理時間未設定")
        Text(recipe.lastCookedAt?.let { "最終調理日 $it" } ?: "調理履歴なし")
        InfoCard("材料・2人分") { state.rows.ingredients.filter { it.recipeId==id }.sortedBy { it.displayOrder }.forEach { Text("${it.name}：${it.amount ?: "数量不明"} ${it.unit}\n${it.note}") } }
        Text("栄養の目安：${recipe.nutritionGroups.split(',').filter { it.isNotBlank() }.joinToString { groupNames[it] ?: it }.ifBlank { "分類なし" }}")
        Text(recipe.memo)
        recipe.sourceUrl?.let { url->Btn("作り方のURLを開く") { vm.action { withContext(Dispatchers.Main) { uri.openUri(url) } } } }
        Btn("編集",true) { vm.openRecipe(id) }; Btn("参照を確認して削除") { vm.deleteRecipe(recipe) }
    }
}
@Composable private fun RecipeEditorV2(vm:AppViewModelV2,state:AppStateV2) {
    val editor=state.editor ?: return
    Page("レシピ編集",{vm.navigate("recipes")}) {
        Text(if(editor.dirty) "下書きを保存中" else "下書き保存済み",style=MaterialTheme.typography.bodySmall)
        FormV2(vm,state,editor.payload.path("form"),null)
        Btn("内容を確認して保存",true) { vm.saveRecipe() }
        Btn("下書きを保持して戻る") { vm.navigate("recipes") }
        editor.row?.let { row->Btn("下書きを破棄") { vm.discardDraft(row) } }
    }
}
@Composable private fun FormV2(vm:AppViewModelV2,state:AppStateV2,form:JsonNode,importKey:String?) {
    var pickFood by remember { mutableStateOf(false) }
    fun edit(key:String,value:String)=vm.formEdit(key,value,importKey)
    Field("料理名",form.path("name").asText(),{edit("name",it)})
    Choice("カテゴリ",form.path("category").asText(),categoriesV2,{edit("category",it)})
    if(importKey==null) Choice("提案優先度",form.path("proposalPriority").asText(),prioritiesV2,{edit("proposalPriority",it)})
    Field("調理時間（分）",form.path("cookingTimeMinutes").asText(),{edit("cookingTimeMinutes",it)})
    Choice("手間",form.path("effortLevel").asText(),efforts,{edit("effortLevel",it)})
    Field("作り方のURL",form.path("sourceUrl").asText(),{edit("sourceUrl",it)})
    Field("メモ",form.path("memo").asText(),{edit("memo",it)},true)
    Text("材料・2人分",style=MaterialTheme.typography.titleMedium)
    form.path("ingredients").forEach { ingredient->
        val key=ingredient.path("localKey").asText()
        fun ingredientEdit(prop:String,value:String)=vm.edit { payload->
            val f=if(importKey==null) payload.path("form") else payload.path("entries").single { it.path("localKey").asText()==importKey }.path("form")
            (f.path("ingredients").single { it.path("localKey").asText()==key } as ObjectNode).put(prop,value)
        }
        InfoCard(ingredient.path("name").asText().ifBlank { "材料" }) {
            Field("材料名",ingredient.path("name").asText(),{ingredientEdit("name",it)})
            if(!ingredient.path("foodMasterId").isNull) Text("選択材料：${state.rows!!.foods.find { it.id==ingredient.path("foodMasterId").asText() }?.name}")
            if(!ingredient.path("foodCandidateKey").isNull) Text("新規材料候補・確定時に登録")
            Field("分量（不明なら空欄）",ingredient.path("amount").asText(),{ingredientEdit("amount",it)})
            Field("単位",ingredient.path("unit").asText(),{ingredientEdit("unit",it)})
            Field("注記",ingredient.path("note").asText(),{ingredientEdit("note",it)})
            Btn("この材料行を除く") { vm.edit { payload->
                val f=if(importKey==null) payload.path("form") else payload.path("entries").single { it.path("localKey").asText()==importKey }.path("form")
                val array=f.path("ingredients") as ArrayNode
                array.remove(array.indexOfFirst { it.path("localKey").asText()==key })
            } }
        }
    }
    Btn("材料を検索・追加") { pickFood=true }
    state.editor!!.payload.path("foodCandidates").forEach { candidate->
        val key=candidate.path("localKey").asText()
        InfoCard("新規材料候補：${candidate.path("name").asText()}") {
            Field("候補の名称",candidate.path("name").asText(),{ value->vm.edit { p->(p.path("foodCandidates").single { it.path("localKey").asText()==key } as ObjectNode).put("name",value) } })
            Field("候補の基本単位",candidate.path("baseUnit").asText(),{ value->vm.edit { p->(p.path("foodCandidates").single { it.path("localKey").asText()==key } as ObjectNode).put("baseUnit",value) } })
            Text("食品群（複数選択可）")
            groupNames.forEach { (group,label)->Check(label,candidate.path("foodGroups").any { it.asText()==group.lowercase() },{ checked->vm.edit { p->
                val c=p.path("foodCandidates").single { it.path("localKey").asText()==key } as ObjectNode
                val groups=c.path("foodGroups").map { it.asText() }.toMutableSet()
                if(checked) groups.add(group.lowercase()) else groups.remove(group.lowercase())
                c.putArray("foodGroups").also { a->groups.forEach(a::add) }
            } }) }
        }
    }
    if(pickFood) FoodPicker(state,{pickFood=false},{food->vm.addIngredient(food,importKey=importKey);pickFood=false},{new->vm.addIngredient(null,new,importKey);pickFood=false})
}
@Composable private fun FoodsV2(vm:AppViewModelV2,state:AppStateV2) {
    var query by rememberSaveable { mutableStateOf("") }
    val normalized=TextNormalizer.normalize(query)
    val aliases=(state.rows!!.builtInAliases.map { it.normalizedAlias to it.foodMasterId }+state.rows.userAliases.map { it.normalizedAlias to it.foodMasterId }).filter { it.first==normalized }.map { it.second }.toSet()
    val matched=state.rows.foods.filter { it.normalizedName.contains(normalized)||it.id in aliases }.sortedBy { it.normalizedName }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("材料",style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=20.dp)); Field("材料名・別名で検索",query,{query=it})
            Btn("利用者材料を登録",true) { vm.navigate("food/new") }; Text("材料の属性と料理で使用する分量は別に管理します。")
        }
        items(matched,key={it.id}) { food->InfoCard(food.name) {
            Text(if(food.userEditable) "利用者材料" else "内蔵プリセット・属性保護")
            Text("食品群：${state.rows.groups.filter { it.foodMasterId==food.id }.joinToString { groupNames[it.foodGroup] ?: it.foodGroup }.ifBlank { "分類なし" }}")
            Btn("「${food.name}」の詳細・編集") { vm.navigate("food/${food.id}") }
        } }
        if(matched.isEmpty()) item { Text("該当する材料がありません。名称や別名を確認してください。") }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
@Composable private fun FoodEditorV2(vm:AppViewModelV2,state:AppStateV2,id:String?) {
    val rows=state.rows!!; val food=id?.let { key->rows.foods.find { it.id==key } }
    if(id!=null&&food==null) return
    val editable=food?.userEditable!=false
    var name by rememberSaveable { mutableStateOf(food?.name.orEmpty()) }
    var aliases by rememberSaveable { mutableStateOf(rows.userAliases.filter { it.foodMasterId==id }.joinToString("\n") { it.alias }) }
    var unit by rememberSaveable { mutableStateOf(food?.baseUnit.orEmpty()) }
    var main by rememberSaveable { mutableStateOf(food?.isMainIngredient ?: true) }
    var groups by remember { mutableStateOf(rows.groups.filter { it.foodMasterId==id }.map { it.foodGroup }.toSet()) }
    var seasons by remember { mutableStateOf(rows.seasons.filter { it.foodMasterId==id }.map { it.month }.toSet()) }
    var merge by remember { mutableStateOf(false) }
    Page("材料の詳細・編集",{vm.navigate("foods")}) {
        Text(if(editable) "利用者材料" else "内蔵プリセット：名称・属性は変更できません")
        Field("材料名",name,{name=it},enabled=editable)
        Field("利用者別名（1行に1つ）",aliases,{aliases=it},true)
        if(!editable) Text("内蔵別名：${rows.builtInAliases.filter { it.foodMasterId==id }.joinToString { it.alias }}")
        Field("基本単位（換算は行いません）",unit,{unit=it},enabled=editable)
        Check("主要食材",main,{main=it},editable)
        Text("食品群（複数選択可）")
        groupNames.forEach { (key,label)->Check(label,key in groups,{groups=if(it) groups+key else groups-key},editable) }
        Text("旬の月")
        (1..12).forEach { month->Check("${month}月",month in seasons,{seasons=if(it) seasons+month else seasons-month},editable) }
        Btn(if(editable) "材料を保存" else "利用者別名を保存",true) {
            vm.saveFood(FoodInputV2(name,aliases.lines().filter { it.isNotBlank() },groups,unit,seasons,main,food?.id,food?.revision))
        }
        if(food?.userEditable==true) {
            Btn("統合先の材料を選ぶ") { merge=true }
            Btn("参照を確認して材料を削除") { vm.deleteFood(food) }
        }
    }
    if(merge) FoodPicker(state,{merge=false},{target->merge=false;vm.mergeFood(food!!,target)},exclude=food!!.id)
}
@Composable private fun ShoppingV2(vm:AppViewModelV2,state:AppStateV2) {
    Page("買い物材料リスト",{vm.navigate("menu")}) {
        WeekControls(vm,state)
        if(state.shopping?.needsRegeneration==true) InfoCard("更新が必要です") { Text("保存済み献立または関連する材料が変更されています。再生成してください。") }
        state.shopping?.warnings?.forEach { Text(it) }
        Btn("献立から生成",true) { vm.generateShopping() }
        val items=state.shopping?.data?.items.orEmpty()
        if(items.isEmpty()) Text("買い物項目がありません。必要日の献立から生成してください。")
        items.forEach { item->InfoCard(item.name) {
            Text(when { item.amount==null->"数量不明 ${item.unit}"; item.hasUnknownAmount->"既知小計 ${item.amount} ${item.unit}・不明あり"; else->"${item.amount} ${item.unit}" })
            val usages=jp.local.recipemanager.core.json.JsonInput.mapper().readTree(item.recipeUsagesJson)
            Text("利用料理：${usages.joinToString { it.path("recipeNameSnapshot").asText() }}")
            Check("「${item.name} ${item.unit}」購入済み",item.checked,{vm.checkItem(item,checked=it)})
            Check("「${item.name} ${item.unit}」購入不要",item.excluded,{vm.checkItem(item,excluded=it)})
        } }
    }
}
@Composable private fun HistoryV2(vm:AppViewModelV2,state:AppStateV2) {
    Page("調理履歴",{vm.navigate("more")}) {
        Btn("過去の実績を追加",true) { vm.navigate("historyEdit/new") }
        state.rows!!.histories.sortedByDescending { it.cookedDate }.forEach { header->
            InfoCard(header.cookedDate) {
                state.rows.historyItems.filter { it.historyId==header.id }.forEach { item->
                    Text("${labelRole(item.role)}：${item.recipeNameSnapshot}")
                    Btn("「${item.recipeNameSnapshot}」の履歴を訂正") { vm.navigate("historyEdit/${item.id}") }
                    Btn("「${item.recipeNameSnapshot}」の履歴を削除") { vm.deleteHistory(item) }
                }
            }
        }
        if(state.rows.histories.isEmpty()) Text("調理履歴なし")
    }
}
@Composable private fun HistoryEditorV2(vm:AppViewModelV2,state:AppStateV2,id:String?) {
    val item=id?.let { key->state.rows!!.historyItems.single { it.id==key } }
    var date by rememberSaveable { mutableStateOf(item?.let { old->state.rows!!.histories.single { it.id==old.historyId }.cookedDate } ?: LocalDate.now().toString()) }
    var recipe by rememberSaveable { mutableStateOf(item?.recipeId) }; var pick by remember { mutableStateOf(false) }
    Page("実際の調理履歴",{vm.navigate("history")}) {
        Field("実際の調理日（YYYY-MM-DD）",date,{date=it})
        Btn(recipe?.let { name(state,it) } ?: "作った料理を選択") { pick=true }
        Btn("履歴を保存",true,recipe!=null) { vm.recordManual(date,recipe!!,item) }
    }
    if(pick) RecipePicker(state,"作った料理",all=true,onDismiss={pick=false}) { recipe=it.id;pick=false }
}
@Composable private fun ProposalV2(vm:AppViewModelV2,state:AppStateV2) {
    val editor=state.editor ?: return
    var ack by remember(editor.row?.updatedAt) { mutableStateOf(false) }
    Page("提案確認",{vm.navigate("menu")}) {
        Text("未採用のため、保存済み献立は変更されていません。固定・経過済み日・結果記録済み日は維持します。")
        state.proposalWarnings.forEach { Text("確認：$it") }
        editor.payload.path("days").forEach { day->InfoCard(day.path("date").asText()) {
            Text(if(day.path("noMeal").asBoolean()) "不要" else day.path("items").joinToString("\n") { "${labelRole(it.path("role").asText())}：${name(state,it.path("recipeId").asText())}${if(it.path("locked").asBoolean()) "（固定）" else ""}" }.ifBlank { "献立未設定" })
        } }
        Check("表示された警告を確認しました",ack,{ack=it})
        Btn("確認して採用",true,state.proposalWarnings.isEmpty()||ack) { vm.adoptProposal(ack) }
        Btn("下書きを保持して戻る") { vm.navigate("menu") }
        editor.row?.let { row->Btn("提案を取り消して破棄") { vm.discardDraft(row) } }
    }
}
@Composable private fun ImportV2(vm:AppViewModelV2,state:AppStateV2) {
    val launcher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.importFile(it) }
    var acknowledged by remember(state.editor?.row?.updatedAt) { mutableStateOf(emptySet<String>()) }
    Page("レシピImport",{vm.navigate("more")}) {
        Btn("Importファイルを選択") { launcher.launch(jsonDocumentMimeTypes) }
        val editor=state.editor?.takeIf { it.kind=="import" }
        if(editor==null) {
            Text("形式1.0のレシピJSONを選択してください。読込上限は5MiBです。")
            state.rows!!.drafts.find { it.kind=="import" }?.let { row->Btn("保存中のImportを再開") { vm.openDraft(row) } }
        } else {
            Text(if(editor.dirty) "修正を保存中" else "Import下書き保存済み")
            editor.payload.path("entries").forEach { entry->
                val key=entry.path("localKey").asText()
                InfoCard(entry.path("form").path("name").asText().ifBlank { "入力を修正してください" }) {
                    FormV2(vm,state,entry.path("form"),key)
                    Choice("登録方法",entry.path("decision").asText(),mapOf("add" to "新規追加","skip" to "スキップ"),{ value->vm.edit { payload->(payload.path("entries").single { it.path("localKey").asText()==key } as ObjectNode).put("decision",value) } })
                    entry.path("errors").forEach { Text("修正：${it.asText()}") }
                    val review=state.importReview?.entries?.find { it.localKey==key }
                    review?.duplicates?.forEach { Text("重複候補：${it.name}${if(it.retired) "（廃止）" else ""}・${it.reason}") }
                    if(review?.duplicateEntryKeys?.isNotEmpty()==true) Text("同じファイル内にも重複候補があります。")
                    if(review?.duplicates?.isNotEmpty()==true||review?.duplicateEntryKeys?.isNotEmpty()==true)
                        Check("重複候補を確認して別IDで新規追加",key in acknowledged,{acknowledged=if(it) acknowledged+key else acknowledged-key})
                }
            }
            Btn("修正内容を再検証") { vm.revalidateImport() }
            Btn("確認した内容を登録",true,state.importReview!=null&&!editor.dirty) { vm.confirmImport(acknowledged) }
            Btn("下書きを保持して戻る") { vm.navigate("more") }
            editor.row?.let { row->Btn("Import下書きを破棄") { vm.discardDraft(row) } }
        }
    }
}
@Composable private fun DraftsV2(vm:AppViewModelV2,state:AppStateV2) {
    Page("保存中の下書き",{vm.navigate("more")}) {
        if(state.rows!!.drafts.isEmpty()) Text("保存中の下書きはありません。")
        state.rows.drafts.forEach { row->
            val payload=jp.local.recipemanager.core.json.JsonInput.mapper().readTree(row.payloadJson)
            val target=when(row.kind) { "recipe"->payload.path("form").path("name").asText().ifBlank { "未命名" }; "menu"->row.targetKey; else->"${payload.path("entries").size()}件" }
            val title="${mapOf("recipe" to "レシピ編集","import" to "Import確認","menu" to "献立提案")[row.kind]}・$target"
            InfoCard(title) {
                Text("更新 ${row.updatedAt}"); Btn("「$title」を再開") { vm.openDraft(row) }; Btn("破棄") { vm.discardDraft(row) }
            }
        }
    }
}
@Composable private fun BackupV2(vm:AppViewModelV2,state:AppStateV2) {
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { vm.exportTo(it) }
    val restore=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { vm.readBackup(it) }
    Page("データ管理",{vm.navigate("more")}) {
        InfoCard("バックアップ") {
            Text("全データを形式2.0で保存します。下書きと購入チェックも含みます。")
            Btn("バックアップを保存",true) { save.launch("recipe-manager-backup_${LocalDate.now()}.json") }
        }
        InfoCard("復元") {
            Text("対応形式：1.0・2.0／上限50MiB。内容を確認してから全件置換します。")
            Btn("復元ファイルを選択") { vm.beginRead();restore.launch(jsonDocumentMimeTypes) }
        }
        state.restorePreview?.let { preview->
            InfoCard("復元内容の確認") {
                Text("作成日時 ${preview.createdAt}")
                preview.counts.forEach { (key,value)->Text("$key $value 件") }
                preview.warnings.forEach { Text("注意：$it") }
                Btn("全件置換を確認して復元",true) { vm.restore() }
                Btn("復元を取り消す") { vm.cancelRead() }
            }
        }
        Text("最終バックアップ：${state.rows!!.settings.find { it.key=="lastBackupAt" }?.value?.takeIf { it.isNotBlank() } ?: "未保存"}")
    }
}
@Composable private fun SettingsV2(vm:AppViewModelV2,state:AppStateV2) {
    Page("設定",{vm.navigate("more")}) {
        val settings=state.rows!!.settings.associate { it.key to it.value }
        Choice("バックアップ通知間隔",settings["backupReminderIntervalDays"] ?: "30",listOf(7,14,30,60).associate { it.toString() to "${it}日" },{vm.reminder(it.toInt())})
        Text("祝日情報の収録年：${state.rows.holidays.map { it.year }.distinct().sorted().joinToString()}")
        Text("未収録年は祝日を判定できません。献立の必要／不要を手動で確認してください。")
        Text("端末のライト／ダーク設定と文字サイズに合わせて表示します。")
        Text("栄養情報は食品群による参考情報です。厳密な栄養計算・単位換算は行いません。")
    }
}
