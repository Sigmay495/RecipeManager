package jp.local.recipemanager.app.v2

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import jp.local.recipemanager.RecipeManagerApplication
import jp.local.recipemanager.core.json.JsonInput
import jp.local.recipemanager.data.local.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek
import java.util.UUID

data class DraftEditorV2(val kind: String, val target: String, val payload: ObjectNode, val bases: List<DraftBaseV2>,
    val row: DraftEntityV2?, val version: Long = 0, val dirty: Boolean = false)
data class AppStateV2(
    val ready: Boolean = false, val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val route: String = "home", val rows: BackupRowsV2? = null, val week: MenuWeekV2? = null,
    val date: LocalDate = LocalDate.now(), val shopping: ShoppingWeekV2? = null,
    val editor: DraftEditorV2? = null, val importReview: ImportReviewV2? = null, val proposalWarnings: List<String> = emptyList(),
    val confirmTitle: String? = null, val confirmMessage: String = "", val undoAvailable: Boolean = false,
    val restorePreview: BackupPreviewV2? = null, val pendingId: String? = null,
)

/** Owns serialized UI writes; the old UI/database cannot participate in this runtime. */
class AppViewModelV2(application: Application, val db: RecipeManagerDatabaseV2 = (application as RecipeManagerApplication).databaseV2) : AndroidViewModel(application) {
    val state = MutableStateFlow(AppStateV2())
    val recipes = RecipeRepositoryV2(db)
    val foods = FoodRepositoryV2(db)
    val menus = MenuRepositoryV2(db)
    val history = HistoryRepositoryV2(db)
    val drafts = DraftRepositoryV2(db, application)
    val commits = DraftCommitRepositoryV2(db, application)
    val shopping = ShoppingRepositoryV2(db)
    val backups = BackupRepositoryV2(db, application)
    private val mapper = JsonInput.mapper()
    private val writes = Mutex()
    private var timer: Job? = null
    private var undoTimer: Job? = null
    private var undoToken: NoMealUndoTokenV2? = null
    private var confirmation: (suspend () -> Unit)? = null
    private var readJob: Job? = null
    private var readGeneration = 0L

    init { retryInitialization() }
    fun retryInitialization() = action {
        DatabaseRuntimeV2.initialize(getApplication(), db)
        refresh()
        state.update { it.copy(ready = true) }
    }

    fun action(quiet: Boolean = false, block: suspend () -> Unit): Job = viewModelScope.launch(Dispatchers.IO) {
        writes.withLock {
            if (!quiet) state.update { it.copy(busy = true, error = null) }
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.update { it.copy(error = e.message ?: "処理に失敗しました。再読込してください") } }
            finally { if (!quiet) state.update { it.copy(busy = false) } }
        }
    }
    suspend fun refresh() {
        val date = state.value.date
        val rows = db.backupDao().snapshot()
        val week = menus.week(date)
        val list = shopping.week(date)
        state.update { it.copy(rows = rows, week = week, shopping = list) }
    }
    fun clearNotice() { state.update { it.copy(error = null, message = null) } }
    fun ask(title: String, message: String, block: suspend () -> Unit) {
        confirmation = block
        state.update { it.copy(confirmTitle = title, confirmMessage = message) }
    }
    fun dismissConfirmation() { confirmation = null; state.update { it.copy(confirmTitle = null) } }
    fun confirm(): Job? {
        val task = confirmation ?: return null
        dismissConfirmation()
        return action { task() }
    }
    fun navigate(route: String) = action {
        persistEditor()
        discardUndo()
        state.update { it.copy(route = route, date = if(route == "home") LocalDate.now() else it.date, editor = null, importReview = null, pendingId = null, error = null, message = null) }
        refresh()
    }
    fun changeWeek(offset: Long) = action {
        discardUndo()
        state.update { it.copy(date = monday(it.date).plusWeeks(offset)) }; refresh()
    }
    fun openDay(date: LocalDate, pendingId: String? = null) = action {
        persistEditor(); discardUndo()
        state.update { it.copy(date = date, route = "day", editor = null, pendingId = pendingId) }; refresh()
    }
    fun toggleNeed(day: MenuDayDetailsV2) {
        val week = state.value.week ?: return
        val date = LocalDate.parse(day.day.date)
        val block: suspend () -> Unit = {
            discardUndo()
            if (day.day.noMeal) menus.saveDay(MenuDayInputV2(date, false), week.menu?.revision, revision(day))
            else {
                val change = menus.setNoMealWithUndo(date, week.menu?.revision, revision(day), true)
                undoToken = change.undoToken
                state.update { it.copy(undoAvailable = undoToken != null, message = "不要に変更しました") }
                undoTimer = viewModelScope.launch { delay(8000); discardUndo() }
            }
            refresh()
        }
        val hasRelated = state.value.rows?.outcomes?.any { result -> day.items.any { it.id == result.menuItemId } } == true
        if (!day.day.noMeal && (hasRelated || day.day.dayLocked || day.items.any { it.locked }))
            ask("不要に変更しますか", "この日の料理・固定を除去し、結果と延期の関連を解除します。実際の調理履歴は保持します。", block)
        else action { block() }
    }
    fun undoNeed() = action {
        val token = undoToken ?: error("取消期間が終了しています")
        undoTimer?.cancel(); undoToken = null; state.update { it.copy(undoAvailable = false) }
        menus.undoNoMeal(token); refresh()
    }
    private fun discardUndo() {
        undoTimer?.cancel(); undoToken?.let(menus::discardUndo); undoToken = null
        state.update { it.copy(undoAvailable = false) }
    }
    fun saveDay(input: MenuDayInputV2, day: MenuDayDetailsV2) {
        val week = state.value.week ?: return
        val pending = state.value.pendingId?.let { id -> state.value.rows?.postponements?.find { it.id == id } }
        ask(if (pending == null) "献立を保存しますか" else "延期先を確定しますか",
            "変更する料理の結果・延期の関連は解除します。実際の履歴は保持します。固定・実績の保護により変更できない場合は理由を表示します。") {
            if (pending == null) menus.saveDay(input, week.menu?.revision, revision(day), true)
            else {
                val source = state.value.rows!!.menuItems.single { it.id == pending.sourceMenuItemId }
                val sourceDay = state.value.rows!!.days.single { it.id == source.menuDayId }
                history.confirmPostponement(pending.id, pending.updatedAt, sourceDay.revision, input, week.menu?.revision, revision(day), true)
                state.update { it.copy(pendingId = null) }
            }
            refresh(); state.update { it.copy(message = "献立を保存しました") }
        }
    }
    fun record(day: MenuDayDetailsV2, actual: Map<String,String?>) {
        ask("調理結果を登録しますか", "作った料理だけを実際の調理日で履歴へ追加します。買い物数量は変わりません。") {
            history.recordCooking(day.day.id, day.day.revision, actual); refresh()
        }
    }
    fun postpone(day: MenuDayDetailsV2, item: MenuItemEntityV2, date: String) = action {
        history.postpone(item.id, LocalDate.parse(date), day.day.revision); refresh()
    }
    fun clearOutcome(day: MenuDayDetailsV2, item: MenuItemEntityV2) {
        ask("結果の関連を解除しますか", "実際の調理履歴は保持して、予定を結果未記録へ戻します。") {
            history.clearOutcome(item.id, day.day.revision, true); refresh()
        }
    }
    fun cancelPostponement(pending: PostponedPlanEntityV2) {
        val source = state.value.rows!!.menuItems.single { it.id == pending.sourceMenuItemId }
        val day = state.value.rows!!.days.single { it.id == source.menuDayId }
        ask("延期を取り消しますか", "元の結果を未記録へ戻します。確定済み延期先の料理と実際の履歴は保持します。") {
            history.cancelPostponement(pending.id, pending.updatedAt, day.revision, true); refresh()
        }
    }
    fun recordManual(date: String, recipeId: String, item: HistoryItemEntityV2? = null) {
        val rows = state.value.rows ?: return
        val header = item?.let { old -> rows.histories.single { it.id == old.historyId } }
        ask("履歴を保存しますか", "実際の調理日で保存します。訂正時に関連する予定結果があれば関連を解除し、最終調理日を再計算します。") {
            if (item == null) history.recordManual(LocalDate.parse(date), recipeId)
            else history.editItem(item.id, header!!.updatedAt, LocalDate.parse(date), recipeId,
                if(item.recipeId == recipeId) item.role else rows.recipes.single { it.id == recipeId }.category, true, true)
            refresh(); state.update { it.copy(route = "history") }
        }
    }
    fun deleteHistory(item: HistoryItemEntityV2) {
        val header = state.value.rows!!.histories.single { it.id == item.historyId }
        ask("履歴を削除しますか", "${header.cookedDate} の「${item.recipeNameSnapshot}」を削除し、予定結果との関連を解除します。") {
            history.deleteItem(item.id, header.updatedAt, true, true); refresh()
        }
    }
    fun generateShopping() = action { val result = shopping.generate(state.value.date); refresh(); state.update { it.copy(message = result.warnings.joinToString("\n").ifBlank { "買い物リストを生成しました" }) } }
    fun checkItem(item: ShoppingItemEntityV2, checked: Boolean = item.checked, excluded: Boolean = item.excluded) = action {
        shopping.updateState(item, checked, excluded); refresh()
    }
    fun saveFood(input: FoodInputV2) = action {
        val current = input.id?.let { foods.find(it)!!.master }
        if (current?.userEditable == false) foods.replaceUserAliases(current.id, input.expectedRevision!!, input.aliases)
        else foods.save(input)
        refresh(); state.update { it.copy(route = "foods", message = "材料を保存しました") }
    }
    fun mergeFood(source: FoodMasterEntityV2, target: FoodMasterEntityV2) {
        ask("材料を統合しますか", "「${source.name}」の参照と別名を「${target.name}」へ移します。異なる単位は合算せず、購入済みは解除します。") {
            foods.merge(source.id, target.id, source.revision, target.revision, true); refresh(); state.update { it.copy(route = "foods") }
        }
    }
    fun deleteFood(food: FoodMasterEntityV2) {
        ask("材料を削除しますか", "「${food.name}」を削除します。参照中・内蔵の材料は削除できません。") {
            foods.delete(food.id, food.revision, true); refresh(); state.update { it.copy(route = "foods") }
        }
    }
    fun deleteRecipe(recipe: RecipeEntityV2) {
        ask("レシピを削除しますか", "「${recipe.name}」を完全削除します。献立・履歴・下書きから参照されている場合は削除できません。") {
            recipes.delete(recipe.id, recipe.revision, true); refresh(); state.update { it.copy(route = "recipes") }
        }
    }
    fun openRecipe(id: String? = null) = action {
        persistEditor(); discardUndo()
        val target = id ?: "new"
        val old = drafts.find("recipe", target)
        if (old != null) loadDraft(old)
        else {
            val details = id?.let { recipes.find(it) ?: error("レシピが見つかりません") }
            val payload = mapper.createObjectNode().apply {
                putArray("foodCandidates"); putArray("conflicts")
                set<ObjectNode>("form", recipeForm(details))
            }
            val bases = drafts.captureBases("recipe", target, payload)
            val row = drafts.save("recipe", target, payload, bases)
            loadDraft(row)
        }
        refresh()
    }
    fun openDraft(row: DraftEntityV2) = action { persistEditor(); discardUndo(); loadDraft(row); refresh() }
    private suspend fun loadDraft(row: DraftEntityV2) {
        val resumed = drafts.resume(row.id)
        val current = resumed.draft
        var editor = DraftEditorV2(current.kind, current.targetKey, drafts.payload(current), drafts.bases(current), current)
        var importing: ImportReviewV2? = null
        var warnings = emptyList<String>()
        var error = resumed.conflicts.joinToString("\n") { it.reason }.ifBlank { null }
        try {
            if (current.kind == "import") {
                val reviewed = commits.revalidateImport(current.id, current.updatedAt)
                editor = editor.copy(payload = drafts.payload(reviewed.draft), bases = drafts.bases(reviewed.draft), row = reviewed.draft)
                importing = reviewed
            } else if (current.kind == "menu" && resumed.canConfirm) {
                warnings = commits.reviewMenu(current.id, current.updatedAt).evaluation.warnings
            }
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { error = listOfNotNull(error,e.message).joinToString("\n") }
        // Publish once: late validation must never replace edits to an already visible form.
        state.update { it.copy(date = if(current.kind == "menu") LocalDate.parse(current.targetKey) else it.date,
            route = when(current.kind) { "recipe" -> "recipeEdit"; "import" -> "import"; else -> "proposal" },
            editor = editor, importReview = importing, proposalWarnings = warnings, error = error) }
    }
    fun edit(transform: (ObjectNode) -> Unit) {
        state.update { current ->
            val old = current.editor ?: return@update current
            val payload = old.payload.deepCopy().also(transform)
            current.copy(editor = old.copy(payload = payload, version = old.version + 1, dirty = true), importReview = null)
        }
        timer?.cancel()
        timer = viewModelScope.launch { delay(300); action(quiet = true) { persistEditor() } }
    }
    fun formEdit(key: String, value: String, importKey: String? = null) = edit {
        val form = if (importKey == null) it.path("form") else it.path("entries").single { row -> row.path("localKey").asText() == importKey }.path("form")
        (form as ObjectNode).put(key,value)
    }
    fun addIngredient(food: FoodMasterEntityV2?, candidateName: String? = null, importKey: String? = null) = edit { payload ->
        val form = if (importKey == null) payload.path("form") else payload.path("entries").single { it.path("localKey").asText() == importKey }.path("form")
        var candidateKey: String? = null
        if (food == null && candidateName != null) {
            candidateKey = UUID.randomUUID().toString()
            payload.withArray("foodCandidates").addObject().apply {
                put("localKey", candidateKey); put("name", candidateName); putArray("aliases"); putArray("foodGroups")
                put("baseUnit",""); putArray("seasonMonths"); put("isMainIngredient",true)
            }
        }
        (form as ObjectNode).withArray("ingredients").addObject().apply {
            put("localKey",UUID.randomUUID().toString()); put("name", food?.name ?: candidateName.orEmpty())
            put("amount",""); put("unit",food?.baseUnit.orEmpty()); put("note","")
            if (food == null) putNull("foodMasterId") else put("foodMasterId",food.id)
            if (candidateKey == null) putNull("foodCandidateKey") else put("foodCandidateKey",candidateKey)
        }
    }
    private suspend fun persistEditor() {
        val old = state.value.editor ?: return
        if (!old.dirty) return
        val existing = old.bases.associateBy { it.entityType to it.entityId }
        val captured = drafts.captureBases(old.kind, old.target, old.payload)
        val bases = old.bases + captured.filter { (it.entityType to it.entityId) !in existing }
        val row = drafts.save(old.kind, old.target, old.payload, bases, old.row?.updatedAt)
        state.update { current ->
            val editor = current.editor
            if (editor == null || editor.kind != old.kind || editor.target != old.target) current
            else current.copy(editor = editor.copy(row = row, bases = bases, dirty = editor.version != old.version))
        }
    }
    fun flushDraft() = action(quiet = true) { persistEditor() }
    fun discardDraft(row: DraftEntityV2) {
        ask("下書きを破棄しますか", "この下書きを完全に破棄します。確定済みデータは変更しません。") {
            timer?.cancel()
            val active = state.value.editor?.row?.takeIf { it.id == row.id }
            if (active != null) persistEditor()
            val current = drafts.all().single { it.id == row.id }
            drafts.discard(current.id, current.updatedAt, true)
            state.update { it.copy(editor = null, route = "drafts") }; refresh()
        }
    }
    fun saveRecipe() = action {
        persistEditor()
        val editor = state.value.editor ?: error("下書きがありません")
        val saved = commits.confirmRecipe(editor.row!!.id, editor.row.updatedAt, true)
        state.update { it.copy(editor = null, route = "recipe/${saved.id}", message = "レシピを保存しました") }; refresh()
    }
    fun importFile(uri: Uri?) {
        if (uri == null) {
            action {
                persistEditor()
                state.update { it.copy(editor = null, importReview = null, message = "ファイル選択を取り消しました。保存済み下書きから再開できます。") }
                refresh()
            }
            return
        }
        val old = state.value.editor?.takeIf { it.kind == "import" }?.row ?: state.value.rows?.drafts?.find { it.kind == "import" }
        val task: suspend () -> Unit = {
            persistEditor()
            val expected = state.value.editor?.row?.takeIf { it.id == old?.id } ?: old
            state.update { it.copy(editor = null, importReview = null) }
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { stream ->
                val result = commits.beginImport(stream, expected?.updatedAt, old != null)
                loadDraft(result.draft)
            } ?: error("ファイルを開けませんでした")
            refresh()
        }
        if (old != null) ask("Import下書きを置き換えますか", "現在のImport確認内容を、新しく読み込むファイルで置き換えます。", task)
        else action { task() }
    }
    fun revalidateImport() = action {
        persistEditor(); val editor = state.value.editor ?: error("Import下書きがありません")
        val reviewed = commits.revalidateImport(editor.row!!.id, editor.row.updatedAt)
        state.update { it.copy(editor = editor.copy(row = reviewed.draft, payload = drafts.payload(reviewed.draft)), importReview = reviewed) }; refresh()
    }
    fun confirmImport(acknowledged: Set<String>) {
        ask("Importを確定しますか", "「新規追加」の料理と材料候補を登録します。既存料理の上書きや廃止解除は行いません。") {
            persistEditor(); val editor = state.value.editor!!
            val result = commits.confirmImport(editor.row!!.id, editor.row.updatedAt, true, acknowledged)
            state.update { it.copy(editor = null, importReview = null, route = "recipes", message = "${result.addedRecipeIds.size}件を追加、${result.skipped}件をスキップしました") }; refresh()
        }
    }
    fun generateProposal() = action {
        persistEditor()
        val start = monday(state.value.date)
        val old = drafts.find("menu", start.toString())
        if (old != null) { loadDraft(old); return@action }
        val preview = MenuProposalRepositoryV2(db).preview(start, System.currentTimeMillis())
        val result = preview.result
        require(result is ProposalResultV2.Success) { (result as ProposalResultV2.Failure).message }
        val payload = mapper.createObjectNode().apply {
            put("seed",result.seed.toString()); putArray("conflicts")
            putArray("warnings").also { a -> result.evaluation.warnings.forEach(a::add) }
            putArray("days").also { a -> result.days.forEach { day -> a.addObject().apply {
                put("date",day.date.toString()); put("noMeal",day.noMeal); put("dayLocked",day.dayLocked)
                put("requirementOrigin", preview.request.days.single { it.date == day.date }.let { state.value.week!!.days.single { row -> row.day.date == it.date.toString() }.day.requirementOrigin })
                putArray("items").also { items -> day.items.forEach { item -> items.addObject().apply {
                    put("localKey",UUID.randomUUID().toString()); put("recipeId",item.recipeId); put("role",item.role.lowercase()); put("locked",item.locked)
                } } }
            } } }
        }
        val row = drafts.save("menu",start.toString(),payload,drafts.captureBases("menu",start.toString(),payload))
        loadDraft(row); refresh()
    }
    fun adoptProposal(acknowledged: Boolean) = action {
        persistEditor(); val row = state.value.editor!!.row!!
        val review = commits.reviewMenu(row.id,row.updatedAt)
        state.update { it.copy(proposalWarnings = review.evaluation.warnings) }
        require(acknowledged || review.evaluation.warnings.isEmpty()) { "警告を確認してください" }
        commits.confirmMenu(row.id,row.updatedAt,true, if(acknowledged) review.evaluation.warnings.toSet() else emptySet())
        state.update { it.copy(editor = null, route = "menu", message = "提案を採用しました") }; refresh()
    }
    fun exportTo(uri: Uri?) = action {
        if(uri == null) { state.update { it.copy(message = "バックアップの保存を取り消しました") }; return@action }
        persistEditor()
        when(val result = backups.exportTo { getApplication<Application>().contentResolver.openOutputStream(uri,"wt") ?: error("保存先を開けませんでした") }) {
            is BackupWriteResultV2.Saved -> state.update { it.copy(message = "バックアップを保存しました") }
            is BackupWriteResultV2.Cancelled -> state.update { it.copy(message = "保存を取り消しました") }
            is BackupWriteResultV2.Failed -> error(result.message)
        }
        refresh()
    }
    fun beginRead() { readJob?.cancel(); readGeneration++; state.update { it.copy(restorePreview = null, error = null, message = null) } }
    fun cancelRead() { beginRead(); state.update { it.copy(message = "ファイル選択を取り消しました") } }
    fun readBackup(uri: Uri?) {
        beginRead()
        if(uri == null) { cancelRead(); return }
        val generation = readGeneration
        readJob = action {
            persistEditor()
            val result = getApplication<Application>().contentResolver.openInputStream(uri)?.let { backups.read(it) } ?: error("読込先を開けませんでした")
            if(generation != readGeneration) return@action
            when(result) {
                is BackupPreviewResultV2.Ready -> state.update { it.copy(restorePreview = result.preview) }
                is BackupPreviewResultV2.Rejected -> error(result.message)
            }
        }
    }
    fun restore() {
        val preview = state.value.restorePreview ?: return
        ask("現在の全データを置き換えますか", "レシピ・献立・材料・履歴・下書き・買い物状態をファイルの内容で置き換えます。元には戻せません。") {
            timer?.cancel(); discardUndo(); backups.restore(preview,true)
            state.update { it.copy(editor = null, importReview = null, restorePreview = null, route = "home", pendingId = null, message = "復元しました") }; refresh()
        }
    }
    fun reminder(days: Int) = action { require(days in setOf(7,14,30,60)); db.backupDao().putSetting("backupReminderIntervalDays",days.toString()); refresh() }
    private fun recipeForm(details: RecipeDetailsV2?): ObjectNode = mapper.createObjectNode().apply {
        val r = details?.recipe
        put("name",r?.name.orEmpty()); put("category",r?.category?.lowercase() ?: "one_dish")
        put("cookingTimeMinutes",r?.cookingTimeMinutes?.toString().orEmpty()); put("effortLevel",r?.effortLevel?.lowercase() ?: "normal")
        put("sourceUrl",r?.sourceUrl.orEmpty()); put("memo",r?.memo.orEmpty()); put("proposalPriority",r?.proposalPriority ?: "normal")
        putArray("ingredients").also { a -> details?.ingredients?.sortedBy { it.displayOrder }?.forEach { i -> a.addObject().apply {
            put("localKey",i.id); put("name",i.name); put("amount",i.amount.orEmpty()); put("unit",i.unit); put("note",i.note)
            if(i.foodMasterId == null) putNull("foodMasterId") else put("foodMasterId",i.foodMasterId)
            putNull("foodCandidateKey")
        } } }
    }
    private fun revision(day: MenuDayDetailsV2) = day.day.revision.takeIf { day.day.id.isNotEmpty() }
    private fun monday(date: LocalDate) = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
}
